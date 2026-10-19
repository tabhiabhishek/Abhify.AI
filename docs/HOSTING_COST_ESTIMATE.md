# Hosting cost estimate (architecture B: managed web + self-hosted Ollama)

Date: 2026-10-19. **Estimates only. Nothing is bought or provisioned.** Prices change and differ by region, tax and currency, so re-check on the provider's own page before any decision.

Labels used below:
- ✅ **verified:** read on the provider's official page during this review
- 📄 **official, unconfirmed:** Google's figure, but seen only in a search snippet because the page didn't render for my tool
- 🔶 **third-party:** aggregator or blog; may be outdated
- ✏️ **estimate:** my own calculation from the figures and assumptions shown

## 1. Resource needs (from the current app)
| Component | Need | Basis |
|---|---|---|
| LLM | `qwen2.5` 7B Q4 (~4.7 GB) fits a **16–24 GB GPU** with room for context; `qwen2.5:1.5b` runs on CPU but is weaker | Measured on the dev PC: 7B ran **100% on the GPU** (RTX A2000 12 GB), ~2.2 s per short answer |
| Embeddings | `nomic-embed-text` (~0.3 GB), same host | Current app |
| Web/API container | 1 vCPU, 1–2 GiB RAM, no GPU (no Whisper in the hosted edition at first) | FastAPI + PWA |
| Database | PostgreSQL + pgvector, a few GB at pilot scale | Text and embeddings, per user |
| Model weights | ~5–10 GB disk on the inference host | Models pulled by the owner, pinned |

## 2. Inference host options (the dominant cost)
| Option | GPU / VRAM | Price | Cold start | Notes |
|---|---|---|---|---|
| **Cloud Run GPU (serverless, scale to zero)** | NVIDIA L4, 24 GB ✅; needs ≥ 4 vCPU / 16 GiB (8 / 32 recommended) ✅ | 📄 L4 **$0.0001867 / s** (≈ $0.67/h ✏️) without zonal redundancy, $0.0002909/s with; plus CPU and memory, billed for the **whole instance lifetime** (instance-based billing) ✅ | ~5 s for the instance ✅ + model load (Google reports ~19 s time-to-first-token from zero for a 4B model 🔶) | L4 regions: asia-southeast1, europe-west1/4, us-central1, us-east4; **asia-south1 (Mumbai) is invitation-only** ✅. Starting quota: 3 GPUs per region ✅ |
| **Rented dedicated GPU server** (example: Hetzner) | GEX45: RTX PRO 4000 Blackwell SFF **24 GB**, Helsinki ✅ | GEX45 price: **unknown** (not shown on the page I could read). Predecessor GEX44 (20 GB) launched at **€184/month + €79 setup** ✅ (Hetzner announcement, 2024); later €234 + €114 🔶; now not orderable 🔶 | None (always on) | Fixed monthly price, full control, you patch the OS. EU location means higher latency from India (fine for chat) |
| **Cloud GPU VM, always on** (example: AWS) | g6.xlarge L4 24 GB · g4dn.xlarge T4 16 GB | g6.xlarge ≈ **$0.80/h** (us-east-1), ≈ $587/month; g4dn.xlarge ≈ **$0.53/h**, ≈ $384/month 🔶 | None while running | Stop/start schedules cut cost but add manual operations |
| **Your own second machine at home** (not the dev PC) | Whatever GPU you buy | Hardware + electricity (✏️ not estimated) | None | Cheapest to run; availability depends on home power and internet. Reached only through a private tunnel |

## 3. Web tier, database, storage
| Item | Figure |
|---|---|
| Cloud Run CPU (no GPU), instance-based | 🔶 ~$0.000018 per vCPU-second and ~$0.000002 per GiB-second (us-central1), with a monthly free allowance (amounts vary by billing mode) |
| Managed PostgreSQL (smallest) | 🔶 roughly **$10/month** for the smallest Cloud SQL tier; production sizes cost more. Alternatives: Postgres on the same VM as the inference host (cheaper, more work) |
| Object storage, backups | ✏️ a few dollars per month at pilot scale (GBs, not TBs) |
| Egress (text only) | ✏️ small at pilot scale; grows with users |
| Domain | ✏️ ~$10–20 per year (you register it) |
| Certificates | Free and managed by the platform (Cloud Run custom domains / a reverse proxy with Let's Encrypt) |
| Monitoring and error reporting | ✏️ free tiers at pilot scale |

## 4. Scenarios (✏️ estimates; assumptions stated)
**Assumptions:**
- 7B model on an L4-class GPU, ~3 s of GPU time per answer, average 10 answers per active user per day.
- Serverless instances stay warm ~15 min after the last request.
- Cloud Run instance with L4 + 4 vCPU + 16 GiB ≈ **$1.05 per running hour** (✏️ = $0.67 GPU + ~$0.26 CPU + ~$0.12 memory).

| Scenario | Usage | B-serverless (Cloud Run GPU) | B-dedicated (rented GPU server) | Web + DB + storage |
|---|---|---|---|---|
| **Pilot** (invite-only) | 5–10 users, spread over ~3 h/day → instance runs ~3 h/day | ~90 h/month → **≈ $90–110/month** | **≈ €184–250+/month** (fixed; GEX45 price to confirm) | **≈ $10–30/month** |
| **Medium** | 50–100 users, activity across ~12 h/day | ~360 h → **≈ $380/month**; queueing at peaks | One 24 GB GPU ≈ the same fixed price; queue needed at peaks | **≈ $30–80/month** |
| **Higher / public** | 1,000+ users, all day, peaks of several concurrent chats | Always warm + several instances → **≈ $800–2,500+/month** (needs a quota increase) | 2–4 GPU servers + load balancing → **≈ €400–1,000+/month** | **≈ $100–300+/month** |

**Reading the table:**
- For a **small pilot**, serverless GPU is cheapest, but every first request after a quiet period waits ~20 s.
- From **medium** usage up, a fixed GPU server is usually cheaper and has no cold starts.
- These are planning figures. Run a 2-week pilot with cost alerts before committing.

## 5. Unknowns to resolve before choosing
1. Your country and where users are (region, latency, data rules).
2. The current Hetzner GEX45 price and availability (or another GPU host).
3. Live Cloud Run prices for your region (the official page didn't render for my tool).
4. Expected usage (users, answers per day, document sizes).
5. Whether voice transcription (Whisper) is needed in the hosted edition, which needs GPU or extra CPU.

## Sources
- Google Cloud, *Cloud Run GPU support* (types, VRAM, minimum CPU/memory, regions, scale to zero, quota): https://docs.cloud.google.com/run/docs/configuring/services/gpu
- Google Cloud, *Cloud Run pricing* (L4 per-second rate; page didn't render for me, figure from the search snippet): https://cloud.google.com/run/pricing
- Google Cloud blog, *Cloud Run GPUs are now generally available* (cold start, TTFT example): https://cloud.google.com/blog/products/serverless/cloud-run-gpus-are-now-generally-available
- Hetzner, *GPU server matrix* (GEX45/GEX63/GEX131 specs and locations): https://www.hetzner.com/dedicated-rootserver/matrix-gpu/
- Hetzner, *New GPU server GEX44* (launch price €184 + €79): https://www.hetzner.com/news/new-gpu-server/
- gpuhosted.com, *Hetzner GPU review 2026* (later GEX44 price): https://gpuhosted.com/en/hetzner-gpu-review/
- wz-it.com, *Hetzner GPU servers compared* (GEX44 not orderable, GEX45): https://wz-it.com/en/blog/hetzner-gpu-server-comparison/
- DoiT, *g4dn.xlarge pricing*: https://www.doit.com/compute/compute/aws/us-east-1/g4dn.xlarge
- DoiT, *g6.xlarge pricing*: https://www.doit.com/compute/compute/aws/us-east-1/g6.xlarge
- dev.to, *What Cloud Run costs, with worked examples*: https://dev.to/elimio/what-cloud-run-costs-with-worked-examples-378i
- dev.to, *Moving from a VPS to Cloud Run* (Cloud SQL micro figure): https://dev.to/elimio/moving-from-a-vps-to-cloud-run-what-each-piece-becomes-12ob
