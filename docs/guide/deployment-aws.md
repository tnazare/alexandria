# AWS Deployment

This guide covers deploying Alexandria to AWS for production use. Alexandria runs on your own AWS infrastructure — all data stays within your security boundary.

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                      AWS Account                     │
│                                                     │
│  ┌─────────────┐    ┌──────────────────────────┐   │
│  │  CloudFront │    │        VPC                │   │
│  │  (Frontend) │    │                          │   │
│  └──────┬──────┘    │  ┌──────────────────┐   │   │
│         │           │  │   ECS Fargate     │   │   │
│         │           │  │   (Backend)       │   │   │
│  ┌──────┴──────┐    │  └────────┬─────────┘   │   │
│  │     S3      │    │           │             │   │
│  │  (Static)   │    │  ┌────────┴─────────┐   │   │
│  └─────────────┘    │  │   RDS PostgreSQL  │   │   │
│                     │  │   (Database)      │   │   │
│                     │  └──────────────────┘   │   │
│                     └──────────────────────────┘   │
│                                                     │
│  ┌─────────────┐    ┌─────────────┐               │
│  │   ALB       │    │  Secrets    │               │
│  │  (Backend)  │    │  Manager    │               │
│  └─────────────┘    └─────────────┘               │
└─────────────────────────────────────────────────────┘
```

| Component | AWS Service | Purpose |
|-----------|-------------|---------|
| Backend | ECS Fargate | Runs the Spring Boot API containers |
| Database | RDS PostgreSQL 16 | Persistent data storage |
| Frontend | S3 + CloudFront | Serves static React SPA assets |
| Load Balancer | Application Load Balancer | Routes traffic to ECS tasks |
| Secrets | AWS Secrets Manager | Stores database credentials and OIDC config |
| Networking | VPC + Private Subnets | Network isolation for database and backend |

## Prerequisites

- AWS account with appropriate permissions
- [Terraform](https://www.terraform.io/) 1.5+ installed
- AWS CLI configured with credentials
- An OIDC provider configured (see [OIDC Setup](/guide/oidc-setup))
- A domain name for the deployment (optional but recommended)
- Container images published to a registry (GHCR or ECR)

## Infrastructure Provisioning

Alexandria provides Terraform modules for standard AWS deployment.

### 1. Configure Terraform Variables

Create a `terraform.tfvars` file:

```hcl
# terraform.tfvars

# Region
aws_region = "us-east-1"

# Networking
vpc_cidr = "10.0.0.0/16"

# Database
db_instance_class    = "db.t3.medium"
db_allocated_storage = 20
db_name              = "alexandria"
db_username          = "alexandria"
# Password stored in Secrets Manager (auto-generated if not provided)

# ECS
backend_cpu    = 512
backend_memory = 1024
backend_count  = 2     # Minimum tasks for high availability

# Domain (optional)
domain_name     = "alexandria.your-org.com"
certificate_arn = "arn:aws:acm:us-east-1:123456789:certificate/abc-123"

# OIDC
oidc_issuer_uri = "https://your-org.okta.com/oauth2/default"
oidc_client_id  = "your-client-id"

# Container images
backend_image  = "ghcr.io/alexandria/alexandria-backend:latest"
frontend_image = "ghcr.io/alexandria/alexandria-frontend:latest"
```

### 2. Initialize and Apply

```bash
cd terraform/

terraform init
terraform plan -out=plan.out
terraform apply plan.out
```

### 3. Outputs

After applying, Terraform outputs:

```bash
terraform output

# alb_dns_name     = "alexandria-alb-123456.us-east-1.elb.amazonaws.com"
# cloudfront_url   = "d1234567890.cloudfront.net"
# rds_endpoint     = "alexandria-db.abc123.us-east-1.rds.amazonaws.com"
# ecs_cluster_name = "alexandria-cluster"
```

## Manual Deployment Steps

If not using Terraform, provision these resources manually:

### 1. Create the VPC

- VPC with at least 2 private subnets (for RDS multi-AZ) and 2 public subnets (for ALB)
- NAT Gateway for outbound internet access from private subnets
- Security groups:
  - **ALB**: Inbound 443 from anywhere
  - **ECS**: Inbound 8080 from ALB security group
  - **RDS**: Inbound 5432 from ECS security group only

### 2. Create the RDS Instance

```
Engine:           PostgreSQL 16
Instance class:   db.t3.medium (or larger for production)
Storage:          20 GB gp3 (with autoscaling)
Multi-AZ:         Yes (for production)
Subnet group:     Private subnets only
Security group:   Allow 5432 from ECS security group
Backup:           7-day retention
Encryption:       Enabled (AWS managed key or CMK)
```

### 3. Store Secrets

Create secrets in AWS Secrets Manager:

```bash
aws secretsmanager create-secret \
  --name alexandria/db-password \
  --secret-string "your-db-password"

aws secretsmanager create-secret \
  --name alexandria/oidc-config \
  --secret-string '{"issuer_uri":"https://...","client_id":"..."}'
```

### 4. Create the ECS Cluster and Task Definition

Task definition environment:

```json
{
  "containerDefinitions": [
    {
      "name": "alexandria-backend",
      "image": "ghcr.io/alexandria/alexandria-backend:latest",
      "portMappings": [{ "containerPort": 8080 }],
      "environment": [
        { "name": "SPRING_DATASOURCE_URL", "value": "jdbc:postgresql://rds-endpoint:5432/alexandria" },
        { "name": "SERVER_PORT", "value": "8080" }
      ],
      "secrets": [
        { "name": "SPRING_DATASOURCE_PASSWORD", "valueFrom": "arn:aws:secretsmanager:...:alexandria/db-password" },
        { "name": "OIDC_ISSUER_URI", "valueFrom": "arn:aws:secretsmanager:...:alexandria/oidc-config:issuer_uri::" },
        { "name": "OIDC_CLIENT_ID", "valueFrom": "arn:aws:secretsmanager:...:alexandria/oidc-config:client_id::" }
      ],
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 60
      },
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/alexandria-backend",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "backend"
        }
      }
    }
  ],
  "cpu": "512",
  "memory": "1024",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"]
}
```

### 5. Deploy the Frontend

Build and upload static assets to S3:

```bash
cd frontend
VITE_API_BASE_URL=https://api.alexandria.your-org.com \
VITE_OIDC_AUTHORITY=https://your-org.okta.com/oauth2/default \
VITE_OIDC_CLIENT_ID=your-client-id \
npm run build

# Upload to S3
aws s3 sync dist/ s3://alexandria-frontend-bucket/ --delete

# Invalidate CloudFront cache
aws cloudfront create-invalidation \
  --distribution-id E1234567890 \
  --paths "/*"
```

### 6. Configure the Load Balancer

- Target group: Port 8080, health check path `/actuator/health`
- Listener: HTTPS 443 with ACM certificate
- Forward to ECS target group

## Container Images

Alexandria publishes container images to GitHub Container Registry:

```bash
# Pull latest images
docker pull ghcr.io/alexandria/alexandria-backend:latest
docker pull ghcr.io/alexandria/alexandria-frontend:latest

# Or build from source
docker build -t alexandria-backend ./backend
docker build -t alexandria-frontend ./frontend
```

For private registries (ECR):

```bash
# Create ECR repositories
aws ecr create-repository --repository-name alexandria-backend
aws ecr create-repository --repository-name alexandria-frontend

# Tag and push
docker tag alexandria-backend:latest 123456789.dkr.ecr.us-east-1.amazonaws.com/alexandria-backend:latest
docker push 123456789.dkr.ecr.us-east-1.amazonaws.com/alexandria-backend:latest
```

## Sizing Recommendations

| Deployment Size | Users | ECS Tasks | RDS Instance | Notes |
|-----------------|-------|-----------|--------------|-------|
| Small | < 50 | 1 | db.t3.small | Evaluation or small teams |
| Medium | 50–500 | 2 | db.t3.medium | Most organizations |
| Large | 500+ | 4+ | db.r5.large | High availability, read replicas |

## Monitoring

- **CloudWatch Logs**: Container stdout/stderr streamed to log groups
- **ALB metrics**: Request count, latency, HTTP error rates
- **RDS metrics**: CPU, connections, storage, replication lag
- **ECS metrics**: CPU/memory utilization, task count
- **Health check**: ALB monitors `/actuator/health` on each task

## Updating

To deploy a new version:

```bash
# Update the ECS service with the new image
aws ecs update-service \
  --cluster alexandria-cluster \
  --service alexandria-backend \
  --force-new-deployment

# Rebuild and upload frontend
cd frontend && npm run build
aws s3 sync dist/ s3://alexandria-frontend-bucket/ --delete
aws cloudfront create-invalidation --distribution-id E1234567890 --paths "/*"
```

ECS performs rolling updates by default — new tasks start before old ones drain.

## Security Checklist

- [ ] RDS is in private subnets only (no public access)
- [ ] Database credentials stored in Secrets Manager (not environment variables in plain text)
- [ ] HTTPS enforced on ALB (redirect HTTP → HTTPS)
- [ ] Security groups follow least-privilege (backend only accepts from ALB)
- [ ] ECS task role has minimal permissions (Secrets Manager read, CloudWatch Logs write)
- [ ] S3 bucket for frontend is private (accessed only via CloudFront OAI)
- [ ] CloudFront uses HTTPS with TLS 1.2+
- [ ] Database encryption at rest enabled
- [ ] VPC Flow Logs enabled for network auditing
