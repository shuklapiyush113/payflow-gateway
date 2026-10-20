# Deploy for free: k3s on Oracle Cloud Always Free

Free-tier terms change; check them when you sign up.

## 1. Create the VM

1. Sign up for Oracle Cloud (a card is needed for verification only; stay on Always Free resources).
2. Create a compute instance with shape **VM.Standard.A1.Flex** (Ampere ARM), 4 OCPU, 24 GB RAM, Ubuntu 24.04.
   If your region is out of capacity, retry later or try another availability domain.
3. Keep only SSH (22) open in the security list. Traffic comes in through Cloudflare Tunnel, so no
   HTTP ports need to be public.
4. Oracle can reclaim Always Free instances that stay idle; real traffic or a light cron job keeps it active.

## 2. Install k3s

```bash
curl -sfL https://get.k3s.io | sh -
sudo k3s kubectl get nodes
mkdir -p ~/.kube && sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config && sudo chown $USER ~/.kube/config
```

k3s includes Traefik (ingress) and the `local-path` storage class used by the manifests.

## 3. Deploy

```bash
git clone https://github.com/shuklapiyush113/payflow-gateway.git && cd payflow-gateway
cp deploy/k8s/secret.example.yaml deploy/k8s/secret.yaml   # set a real DB_PASSWORD
kubectl apply -f deploy/k8s/namespace.yaml -f deploy/k8s/secret.yaml
kubectl apply -k deploy/k8s
kubectl -n payflow get pods -w
```

Make the GHCR package public (GitHub → Packages → package settings), or add an image pull secret.

Onboard a merchant. The admin API is not on the ingress, so reach it through a port-forward:

```bash
kubectl -n payflow port-forward svc/payment-service 8080:8080 &
curl -s localhost:8080/admin/merchants -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Demo store"}'
# The response contains apiKey.key (sk_…). It is shown once; store it in the merchant's secrets.
```

## 4. Expose through Cloudflare Tunnel

1. Add a domain to Cloudflare (free plan), or use a free subdomain service.
2. Zero Trust → Networks → Tunnels → create a tunnel, then run the `cloudflared` install command it shows on the VM.
3. Add a public hostname, e.g. `api.yourdomain.com` → `http://localhost:80` (Traefik).
4. Set the same host in `deploy/k8s/payment-service.yaml` (Ingress).

Cloudflare then provides HTTPS, DDoS protection and WAF rules in front of the VM.

## Using managed Postgres instead

Point `DB_URL`, `DB_USER` and `DB_PASSWORD` in `secret.yaml` at Neon or Supabase (both offer
pgvector, which step 4 needs) and remove `postgres.yaml` from `kustomization.yaml`.
