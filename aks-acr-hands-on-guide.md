# AKS + ACR hands-on guide (with sandbox/policy workarounds)

A step-by-step reference for deploying an app to Azure Kubernetes Service (AKS) with images pulled from Azure Container Registry (ACR), including fixes for common permission and policy errors seen in restricted/sandbox subscriptions.

---

## 0. Prerequisites

- Azure CLI installed and logged in (`az login`)
- `kubectl` installed
- An existing Azure Container Registry (or create one first with `az acr create`)
- Environment variables set for convenience:

```bash
$RG   = "<your-resource-group>"
$ACR  = "<your-acr-name>"
```

---

## 1. Create the AKS cluster

```bash
az aks create --resource-group $RG --name aks-demo --node-count 1 --node-vm-size Standard_B2s --generate-ssh-keys --attach-acr $ACR
```

| Flag | Purpose |
|---|---|
| `--resource-group` | Resource group the cluster belongs to |
| `--name` | Name of the AKS cluster |
| `--node-count 1` | Number of worker node VMs in the default node pool |
| `--node-vm-size` | VM SKU for nodes. **Required in policy-restricted subscriptions** — the default size (`Standard_DS2_v2`) may be blocked by an `allowed-vm-scale-set-skus` policy. Check allowed SKUs first if you hit `RequestDisallowedByPolicy` (see Troubleshooting below) |
| `--generate-ssh-keys` | Auto-generates an SSH key pair for the Linux node VMs |
| `--attach-acr` | Attempts to grant the cluster's identity `AcrPull` rights on the ACR via a role assignment |

**Known sandbox issue:** In restricted subscriptions (e.g. Pluralsight/training sandboxes), `--attach-acr` frequently fails with:
```
Could not create a role assignment for ACR. Are you an Owner on this subscription?
```
This happens because the sandbox blocks `Microsoft.Authorization/roleAssignments/write`. The cluster still gets created — you just need to grant registry access another way (see Step 6).

---

## 2. Connect kubectl to the cluster

```bash
az aks get-credentials --resource-group $RG --name aks-demo
```

Downloads the cluster's connection details (API server URL, certs, tokens) and merges them into your local `~/.kube/config`. This is what lets `kubectl` talk to your specific AKS cluster. If a context with the same name already exists locally, you'll be prompted to overwrite — answer `y`.

---

## 3. Deploy the application

```bash
kubectl apply -f deployment.yaml
```

Submits your **Deployment** manifest to Kubernetes. Defines the container image, replica count, and pod spec. Kubernetes schedules the requested number of pods onto nodes and keeps that count running.

> `apiVersion: apps/v1`, `kind: Deployment`

---

## 4. Expose the application

```bash
kubectl apply -f service.yaml
```

Submits your **Service** manifest, giving your pods a stable network endpoint (internal or external, depending on `type`).

> `apiVersion: v1`, `kind: Service` — **not** `apps/v1`. A common copy-paste mistake is leaving `apiVersion: apps/v1` from the Deployment file, which throws:
> ```
> error: resource mapping not found for name: ... no matches for kind "Service" in version "apps/v1"
> ```
> Fix by setting `apiVersion: v1` at the top of `service.yaml`.

---

## 5. Watch pod status

```bash
kubectl get pods -w
```

Streams live pod status (`Pending` → `ContainerCreating` → `Running`, or failure states like `ImagePullBackOff` / `CrashLoopBackOff`). Use `Ctrl+C` to stop watching.

---

## 6. Fix `ImagePullBackOff` / `ErrImagePull` (ACR permission workaround)

If `--attach-acr` failed in Step 1, pods can't authenticate to pull images. Since sandbox policies usually block role assignments, use **ACR admin credentials** as a Kubernetes pull secret instead:

**a. Enable admin access on the registry**
```bash
az acr update --name $ACR --admin-enabled true
```

**b. Retrieve the credentials**
```bash
az acr credential show --name $ACR
```
Returns a `username` and two `passwords`.

**c. Create a Kubernetes image-pull secret**
```bash
kubectl create secret docker-registry acr-secret `
  --docker-server=$ACR.azurecr.io `
  --docker-username=<username> `
  --docker-password=<password>
```

**d. Reference the secret in `deployment.yaml`** (same indent level as `containers:`):
```yaml
spec:
  containers:
    - name: demo-app
      image: <acr-name>.azurecr.io/<image>:<tag>
  imagePullSecrets:
    - name: acr-secret
```

**e. Reapply the deployment**
```bash
kubectl apply -f deployment.yaml
```

Pods should move to `Running` within a minute or so — confirm with `kubectl get pods -w`.

---

## 7. Get the service's external IP

```bash
kubectl get service demo-app-svc
```

Shows the Service's cluster IP, ports, and — if `type: LoadBalancer` — the **external IP** once Azure finishes provisioning the load balancer. Use that IP in a browser to reach your app.

---

## 8. Scale the deployment

```bash
kubectl scale deployment demo-app --replicas=3
```

Changes the number of running pod replicas. Kubernetes reconciles actual vs. desired replica count by creating or terminating pods automatically.

---

## Troubleshooting reference

### `(RequestDisallowedByPolicy)` on VMSS creation
A management-group-level Azure Policy restricts allowed VM SKUs. Check the policy assignment:
```bash
az policy assignment list --management-group <mgmt-group-name> -o table
```
Then use an allowed SKU with `--node-vm-size` (e.g. `Standard_B2s`, `Standard_B1ms`, `Standard_D2s_v3` are common in training sandboxes — confirm against your actual policy or lab instructions).

If the CLI lacks permission to inspect the policy, check the **Azure Portal** → Policy → Assignments → Parameters tab instead.

### `Could not create a role assignment for ACR`
You lack `Microsoft.Authorization/roleAssignments/write` at the required scope. Options:
- Ask a subscription Owner / User Access Administrator to run the role assignment for you
- Or use the admin-credentials workaround in Step 6 (no elevated permission needed)

### `no matches for kind "Service" in version "apps/v1"`
Wrong `apiVersion` in `service.yaml` — Services live in the core `v1` API group, not `apps/v1`. Deployments, ReplicaSets, and StatefulSets use `apps/v1`; Pods, Services, ConfigMaps, and Secrets use `v1`.

### `ImagePullBackOff` / `ErrImagePull`
Almost always an authentication issue between the node and the registry. See Step 6.

---

## Quick command cheat-sheet

```bash
# Cluster lifecycle
az aks create --resource-group $RG --name aks-demo --node-count 1 --node-vm-size <sku> --generate-ssh-keys --attach-acr $ACR
az aks get-credentials --resource-group $RG --name aks-demo
az aks delete --resource-group $RG --name aks-demo --yes --no-wait

# App deployment
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
kubectl get pods -w
kubectl get service demo-app-svc
kubectl scale deployment demo-app --replicas=<n>

# ACR access workaround (sandbox-safe)
az acr update --name $ACR --admin-enabled true
az acr credential show --name $ACR
kubectl create secret docker-registry acr-secret --docker-server=$ACR.azurecr.io --docker-username=<user> --docker-password=<pass>

# Debugging
kubectl describe pod <pod-name>
kubectl logs <pod-name>
az policy assignment list --management-group <mgmt-group-name> -o table
```
