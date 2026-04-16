#!/bin/bash
# ══════════════════════════════════════════════════════════════
#  Aura Guardrail API — Cloud Run Deployment Script
#  Region: asia-south1 (Mumbai) for lowest latency to India
# ══════════════════════════════════════════════════════════════

set -euo pipefail

# Configuration
SERVICE_NAME="aura-guardrail-api"
REGION="asia-south1"
PROJECT_ID="${GCP_PROJECT_ID:-$(gcloud config get-value project)}"
GCS_BUCKET="${GCS_BUCKET:-aura-audit-screenshots-${PROJECT_ID}}"
MIN_INSTANCES=1
MAX_INSTANCES=5
MEMORY="512Mi"
CPU="1"

echo "══════════════════════════════════════════════════"
echo "  🚀 Deploying Aura Guardrail API"
echo "  Project: $PROJECT_ID"
echo "  Region:  $REGION"
echo "  Service: $SERVICE_NAME"
echo "  Bucket:  $GCS_BUCKET"
echo "══════════════════════════════════════════════════"

# Enable required APIs
echo "  Enabling GCP APIs..."
gcloud services enable \
    run.googleapis.com \
    firestore.googleapis.com \
    storage.googleapis.com \
    cloudbuild.googleapis.com \
    --project "$PROJECT_ID" --quiet

# Create GCS bucket for audit screenshots (ignore error if already exists)
echo "  Creating Cloud Storage bucket..."
gsutil mb -p "$PROJECT_ID" -l "$REGION" "gs://$GCS_BUCKET" 2>/dev/null || true

# Deploy from source (Cloud Build)
gcloud run deploy "$SERVICE_NAME" \
    --source . \
    --region "$REGION" \
    --project "$PROJECT_ID" \
    --platform managed \
    --allow-unauthenticated \
    --min-instances "$MIN_INSTANCES" \
    --max-instances "$MAX_INSTANCES" \
    --memory "$MEMORY" \
    --cpu "$CPU" \
    --port 8080 \
    --set-env-vars "ENV=production,GCP_PROJECT_ID=${PROJECT_ID},GCS_BUCKET=${GCS_BUCKET}" \
    --timeout 60 \
    --quiet

# Get the deployed URL
SERVICE_URL=$(gcloud run services describe "$SERVICE_NAME" \
    --region "$REGION" \
    --project "$PROJECT_ID" \
    --format "value(status.url)")

echo ""
echo "══════════════════════════════════════════════════"
echo "  ✅ Deployment Complete!"
echo "  Dashboard:  $SERVICE_URL"
echo "  Guardrail:  $SERVICE_URL/verify_tap"
echo "  Metrics:    $SERVICE_URL/api/metrics"
echo "  Health:     $SERVICE_URL/api/health"
echo "══════════════════════════════════════════════════"
echo ""
echo "  📱 Add to android-edge/local.properties:"
echo "     GUARDRAIL_API_URL=$SERVICE_URL"
echo ""
echo "  📊 Open the Mirror Dashboard:"
echo "     $SERVICE_URL"
echo "══════════════════════════════════════════════════"
