output "cluster_name" {
  description = "GKE 클러스터 이름"
  value       = google_container_cluster.auction.name
}

output "cluster_endpoint" {
  description = "GKE API 서버 엔드포인트"
  value       = google_container_cluster.auction.endpoint
  sensitive   = true
}

output "cluster_location" {
  description = "GKE 클러스터 리전"
  value       = google_container_cluster.auction.location
}

output "registry_url" {
  description = "Artifact Registry Docker 이미지 URL 접두사"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.auction.repository_id}"
}

output "get_credentials_command" {
  description = "kubectl 인증 명령어"
  value       = "gcloud container clusters get-credentials ${google_container_cluster.auction.name} --region ${var.region} --project ${var.project_id}"
}
