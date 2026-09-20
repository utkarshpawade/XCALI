# AWS deployment

Free-tier deployment of the whole stack: one EC2 instance running the frontend,
the Spring Boot backend and Caddy, with Postgres on RDS.

Caddy terminates TLS itself with a Let's Encrypt certificate. CloudFront is still
supported but optional - see "TLS: Caddy or CloudFront" below.

```
Browser
   |  https + wss
[ EC2 t3.micro ]  Elastic IP                   750 hrs/mo free (legacy tier)
   |  Caddy :80 -> :443, Let's Encrypt
   |    /api/*  -> strip /api -> backend  :3001  (Spring Boot, REST)
   |    /ws*    ->              backend  :3001  (Spring Boot, WebSocket)
   |    /*      ->              frontend :3000
   |
[ RDS db.t4g.micro ]  Postgres                 750 hrs/mo + 20 GB (legacy tier)
```

Deliberately **not** used: an Application Load Balancer (no free tier, ~$18/mo)
and a custom VPC with a NAT Gateway (~$32/mo). The default VPC is enough.

## Why one domain

Serving the app and the API from a single CloudFront domain means the browser
treats API calls as same-origin, so there are no CORS preflights, and one
distribution covers both `https://` and `wss://`. The cost is that Caddy has to
strip the `/api` prefix, because the REST API mounts its routes at the root.

## Moving an existing stack to the Spring Boot backend

Stacks provisioned before the migration have `excalidraw-http` and
`excalidraw-ws` ECR repositories but no `excalidraw-backend`. Run
`./provision.sh` once (it only creates what is missing), then `./deploy.sh`.
The rollout's `--remove-orphans` stops the old Node containers and the one-shot
`migrate` job.

Nothing needs doing to the database or the secrets: on first start Flyway sees
the tables Prisma created, records them as version 1 (a baseline) and runs
nothing, and the same `JWT_SECRET` keeps everyone signed in. The old
repositories can be deleted by hand, or are removed by `destroy.sh`.

## Prerequisites

- AWS credentials for EC2, RDS, ECR, IAM (and CloudFront, if used).
  `AdministratorAccess` is simplest; the scripts create IAM roles, so even a
  narrower policy still needs `IAMFullAccess`.
- Docker running locally (images are built here, not on the instance)
- `aws`, `docker`, `ssh`, `git`, `openssl`, `python` on PATH

IAM is eventually consistent: after attaching a policy, a run started
immediately can still fail `AccessDenied` on the first write call.

## Deploy

```bash
cd deploy/aws
export AWS_REGION=ap-south-1        # or wherever you want it

# Skips CloudFront and has Caddy terminate TLS instead. sslip.io resolves
# a-b-c-d.sslip.io to that IP, so no DNS of your own is needed. Let's Encrypt
# refuses to issue for *.compute.amazonaws.com, so the EC2 hostname will not do.
export PUBLIC_DOMAIN=13-127-107-151.sslip.io   # match your Elastic IP

# Accounts on AWS's credit-based Free Plan reject the default 7 with
# FreeTierRestrictionError. 0 disables automated backups altogether.
export BACKUP_RETENTION=1

./provision.sh                      # ~15 min, mostly waiting on RDS
./deploy.sh                         # build, push, roll out
```

### TLS: Caddy or CloudFront

Leave `PUBLIC_DOMAIN` unset and `provision.sh` creates a CloudFront distribution
and uses its `dxxxx.cloudfront.net` name, with Caddy serving plain HTTP behind
it. That path needs an AWS-verified account - new accounts are refused with
`Your account must be verified before you can add new CloudFront resources`
until support clears it.

Set `PUBLIC_DOMAIN` and CloudFront is skipped; Caddy provisions and renews a
Let's Encrypt certificate over the HTTP-01 challenge, which is why the security
group keeps port 80 open alongside 443. Moving between the two later means
rebuilding the frontend image - see the ordering constraint below.

### Running from Git Bash on Windows

Two MSYS behaviours break these scripts if reintroduced:

- Arguments that look like absolute POSIX paths get rewritten into Windows
  paths, so `/aws/service/...` arrives as `C:/Program Files/Git/aws/service/...`
  and `/dev/xvda` likewise. `provision.sh` exports `MSYS2_ARG_CONV_EXCL` for the
  two argument shapes affected. A blanket `'*'` is not usable - it also breaks
  resolving the `aws` launcher itself.
- The AWS CLI emits CRLF. A stray CR survives pipes and `read`, and AWS rejects
  it as a control character. Every script strips it in the `aws_` wrapper, which
  also keeps it out of the generated `.pem`.

`provision.sh` is idempotent — re-run it after a failure and it picks up where it
stopped. It writes `.state.env` (gitignored) holding resource IDs, the generated
`JWT_SECRET` and the database password.

To ship a code change afterwards, only `./deploy.sh` is needed.

## Ordering constraint

`NEXT_PUBLIC_HTTP_BACKEND` and `NEXT_PUBLIC_WS_URL` are inlined into the client
bundle by `next build`. The frontend therefore cannot be built until the public
hostname is known, which is why `provision.sh` settles `PUBLIC_DOMAIN` first and
`deploy.sh` passes it in as a build argument. Changing the public URL
means rebuilding the frontend image, not restarting a container.

## Scaling limits

The backend keeps socket connections in memory with no shared pub/sub, so it
must stay at exactly one replica. Two instances would put users in the same room
on different processes and they would stop seeing each other's shapes. Fixing
that means adding Redis pub/sub; until then, this deployment is capped at one
backend process.

The instance has 1 GB of RAM for the JVM, the Next.js server and Caddy, so
`user-data.sh` adds 2 GB of swap, the backend's heap is capped at 256 MB
(`JAVA_TOOL_OPTIONS` in `docker-compose.prod.yml`, about 260 MB resident in
total) and the frontend's at 256 MB.

## Operating

`provision.sh` opens SSH to the machine's public IP at the time it ran. On a
dynamic connection that changes; re-running `provision.sh` adds the new address
(old rules stay, so prune them occasionally).

```bash
source .state.env

ssh -i "$KEY_FILE" ec2-user@$PUBLIC_IP

# on the box
cd /opt/excalidraw
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend
```

Health checks:

| Check | URL |
| --- | --- |
| Caddy itself | `$PUBLIC_ORIGIN/_health` |
| Backend, via the API route | `$PUBLIC_ORIGIN/api/health` |
| Backend, via the socket route | `$PUBLIC_ORIGIN/ws/health` |

Caddy matches on the Host header, so these must go through `PUBLIC_DOMAIN`; the
raw IP does not match the site block and 404s. On first start Caddy needs a few
seconds to obtain its certificate - `docker compose logs caddy` shows progress.

If you kept CloudFront, a brand new distribution takes 5-15 minutes to propagate
and can 502 while the origin checks already pass.

## Costs after the free tier

Roughly $8/mo for the instance, $12/mo for the database, ~$2/mo for storage and
~$4/mo for the public IPv4 address: about **$28/mo**.

Two things catch people out:

- **Every public IPv4 address is billed hourly**, attached or not, since Feb
  2024. Stopping the instance still costs a few dollars a month unless the
  address is released - and releasing it changes the sslip.io hostname, which
  means rebuilding the frontend image.
- Accounts opened under AWS's **credit-based Free Plan** get a fixed pot of
  credits over a few months rather than 12 months of free EC2/RDS hours. At
  ~$28/mo this stack burns $100 of credits in about three and a half months.
  Billing -> Free tier shows which plan applies.

## Teardown

```bash
./destroy.sh
```

Deletes everything, including the database and every drawing in it, with no final
snapshot. If a CloudFront distribution exists it must be disabled and fully
propagated first, which is why the script waits ~10 minutes; with `PUBLIC_DOMAIN`
set there is no distribution and teardown is quicker.

Releasing the Elastic IP is part of teardown, and skipping it keeps billing.
