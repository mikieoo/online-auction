resource "google_container_cluster" "auction" {
  name     = var.cluster_name
  location = var.region

  # Autopilot 모드: 노드 관리 불필요, Pod requests 기준 과금
  enable_autopilot = true

  network    = google_compute_network.auction_vpc.id
  subnetwork = google_compute_subnetwork.auction_subnet.id

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  # dev 환경에서는 삭제 보호 끔 (terraform destroy 가능하게)
  deletion_protection = var.environment == "prod"

  resource_labels = local.common_labels
}
