# Kubernetes Event‑Driven Autoscaling with Azure Container Apps (Java Spring Boot producer)

This guide reproduces the demo transcript: deploy a background worker as a Container App that scales using an Azure Service Bus queue (KEDA-driven). Instead of the Python producer used in the transcript, this document includes a small Java Spring / standalone Java producer that floods the Service Bus queue so you can observe scaling.

Checklist
- [ ] Azure login and set subscription
- [ ] Create resource group (if needed)
- [ ] Create Azure Container Registry (ACR) and enable admin credentials (demo)
- [ ] Build container image into ACR (az acr build)
- [ ] Create Service Bus namespace and queue
- [ ] Retrieve Service Bus connection string
- [ ] Create Container Apps environment
- [ ] Create Container App with a Service Bus scale rule (min=0, max=N)
- [ ] Run the Java producer to enqueue messages
- [ ] Observe autoscaling and queue drain
- [ ] Optional: cleanup resources

Important notes
- Commands below are PowerShell (Windows). Replace placeholder names with your actual values before running.
- For production, prefer Managed Identity for ACR and Service Bus access instead of enabling admin credentials and storing connection strings as secrets.
- The scale rule uses KEDA under the hood. Cooldown and polling behavior (for example: a default cooldown of several minutes) may delay scaling back to zero after the queue drains.

Variables used in examples (change them):
- $RG = azure resource group
- $LOCATION = azure region (e.g., eastus)
- $ACR = container registry name (globally unique)
- $IMAGE_NAME = container image name (e.g., demo-worker)
- $IMAGE_TAG = container image tag
- $SB_NAMESPACE = service bus namespace name (globally unique)
- $SB_QUEUE = queue name (e.g., orders)
- $CA_ENV = container apps environment name
- $APP_NAME = container app name

Step‑by‑step (PowerShell commands)

1) Login and subscription
```powershell
az login --use-device-code
az account set --subscription "<SUBSCRIPTION_ID_OR_NAME>"
```

```
2) Create a resource group (skip if you already have one)
```powershell
$RG = "myResourceGroup"
$LOCATION = "eastus"
az group create --name $RG --location $LOCATION
```

3) Create or reuse Azure Container Registry (ACR)
```powershell
$ACR = "myacrname"   # choose globally unique lowercase name
az acr create --resource-group $RG --name $ACR --sku Basic --location $LOCATION
# Enable admin (demo only) - for production use managed identity and role assignment
az acr update --name $ACR --resource-group $RG --admin-enabled true
$ACR_LOGIN_SERVER = az acr show --name $ACR --resource-group $RG --query "loginServer" -o tsv
Write-Host "ACR login server: $ACR_LOGIN_SERVER"
```

4) Build your worker container into ACR
- If your worker code is in a subfolder, pass that folder as the build context. Example: if your Dockerfile and worker app are in `./worker`.

```powershell
$IMAGE_NAME = "demo-worker"
$IMAGE_TAG = "0.2"
az acr build --registry $ACR --image "$IMAGE_NAME:$IMAGE_TAG" ./worker
$IMAGE_FULL = "$ACR_LOGIN_SERVER/$IMAGE_NAME:$IMAGE_TAG"
Write-Host "Built image: $IMAGE_FULL"
```

5) Create Service Bus namespace and queue
```powershell
$SB_NAMESPACE = "mysbnamespace"  # globally unique
$SB_QUEUE = "orders"
az servicebus namespace create --resource-group $RG --name $SB_NAMESPACE --location $LOCATION
az servicebus queue create --resource-group $RG --namespace-name $SB_NAMESPACE --name $SB_QUEUE
```

6) Retrieve the Service Bus connection string (RootManageSharedAccessKey)
```powershell
$SB_CONNECTION = az servicebus namespace authorization-rule keys list `
  --resource-group $RG `
  --namespace-name $SB_NAMESPACE `
  --name RootManageSharedAccessKey `
  --query primaryConnectionString -o tsv
Write-Host "Got Service Bus connection string (truncated):" $SB_CONNECTION.Substring(0,40) "..."
```

7) Create Container Apps environment
```powershell
$CA_ENV = "my-containerapps-env"
az extension add --name containerapp
az containerapp env create --name $CA_ENV --resource-group $RG --location $LOCATION
```

8) Get ACR credentials (admin credentials - demo only)
```powershell
$ACR_USERNAME = az acr credential show --name $ACR --resource-group $RG --query username -o tsv
$ACR_PASSWORD = az acr credential show --name $ACR --resource-group $RG --query "passwords[0].value" -o tsv
```

9) Create the Container App with an Azure Service Bus scale rule
- Key ideas: create a secret named SERVICE_BUS_CONNECTION_STRING containing the connection string; pass an environment variable referencing that secret; set scale metadata queueName, namespace, messageCount threshold and connection secret name.

```powershell
$APP_NAME = "worker-app"
az containerapp create `
  --name $APP_NAME `
  --resource-group $RG `
  --environment $CA_ENV `
  --image $IMAGE_FULL `
  --registry-server $ACR_LOGIN_SERVER `
  --registry-username $ACR_USERNAME `
  --registry-password $ACR_PASSWORD `
  --ingress 'none' `
  --min-replicas 0 `
  --max-replicas 5 `
  --secrets SERVICE_BUS_CONNECTION_STRING="$SB_CONNECTION" `
  --env-vars SERVICE_BUS_CONNECTION_STRING='{{secrets.SERVICE_BUS_CONNECTION_STRING}}' `
  --scale-rule-name sb-scaler `
  --scale-rule-type azure-servicebus `
  --scale-rule-metadata queueName=$SB_QUEUE namespace=$SB_NAMESPACE messageCount=5 connection=SERVICE_BUS_CONNECTION_STRING
```

10) Verify the Container App
```powershell
az containerapp show --name $APP_NAME --resource-group $RG --query properties -o json
az containerapp show --name $APP_NAME --resource-group $RG --query "properties.template.scale" -o json
az containerapp revision list --name $APP_NAME --resource-group $RG -o table
```

11) Java producer (Spring Boot) — add your producer to the repo and run locally

Per your request, keep all Java source files under the existing project's main source folder (do not create a separate module). Add your producer class under:

  - `src/main/java/com/acr/demo/producer/` (or another package under `src/main/java` that matches your project layout)

Example notes for the producer class (you will create this file when ready):
  - Read the Service Bus connection string from the environment variable `SERVICE_BUS_CONNECTION_STRING`.
  - Send 100 messages to the queue named `orders` (or a queue name you configured).
  - Exit when done (the producer is a one‑off CLI program for this demo).

Build and run using the repository Maven wrapper (recommended since this repo includes `mvnw`):

```powershell
# Set the connection string in the current PowerShell session
$env:SERVICE_BUS_CONNECTION_STRING = $SB_CONNECTION

# From the repository root, build the project (skip tests for faster runs)
.\mvnw.cmd -DskipTests package

# Run the Spring Boot application (if your app is a Spring Boot app producing messages)
java -jar target\*.jar

# Alternatively, you can run with the spring-boot plugin during development
.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--spring.main.web-application-type=none"
```

Run the standalone producer main class directly with Maven exec (recommended for local producers):
```powershell
# from the repo root - make sure SERVICE_BUS_CONNECTION_STRING is set in the environment
.\mvnw.cmd exec:java -Dexec.mainClass=com.acr.demo.producer.ServiceBusProducer -Dexec.args="100"
```

Notes:
  - If your project produces a fat jar with a known artifactId and version, replace `target\*.jar` with the proper path `target\<artifact>-<version>.jar`.
  - If you want the producer to accept a queue name argument, pass it on the command line as the first argument.

12) Observe scaling
- Portal: go to your Container App resource → Revisions & replicas to see replica count increase while the queue has messages.
- CLI:
```powershell
az servicebus queue show --resource-group $RG --namespace-name $SB_NAMESPACE --name $SB_QUEUE --query "countDetails" -o json
az containerapp revision list --name $APP_NAME --resource-group $RG -o table
az containerapp show --name $APP_NAME --resource-group $RG --query "properties.template.scale" -o json
az containerapp logs show --name $APP_NAME --resource-group $RG --revision latest --follow
```

Notes on scaling behavior
- KEDA polls the queue periodically (typically ~30s) and uses a cooldown (often minutes) before scaling to zero — you may observe a delay between the queue draining and replicas terminating.
- Tweak `messageCount` or other metadata if you want different scale sensitivity.

13) Cleanup (optional)
```powershell
az containerapp delete --name $APP_NAME --resource-group $RG --yes
az containerapp env delete --name $CA_ENV --resource-group $RG --yes
az acr delete --name $ACR --resource-group $RG --yes
az servicebus namespace delete --resource-group $RG --name $SB_NAMESPACE
# or delete resource group to remove everything
az group delete --name $RG --yes --no-wait
```

Files included in this repo
- `producer-java/` — Java producer project (pom.xml and source) that sends 100 messages to Service Bus.

If you prefer, I can also:
- Add a Dockerfile and show how to build and push the Java producer into ACR and run it as a pod in Kubernetes (not required for this demo)
- Show how to replace the ACR admin credentials approach with a managed identity and assign `AcrPull` role

Enjoy — run the producer and watch Container Apps scale!

