resource "google_compute_network" "auction_vpc" {
  name                    = "auction-vpc"
  auto_create_subnetworks = false

  description = "경매 시스템 전용 VPC"
}

resource "google_compute_subnetwork" "auction_subnet" {
  name          = "auction-subnet"
  network       = google_compute_network.auction_vpc.id
  ip_cidr_range = "10.0.0.0/20" # 노드용 (4,096 IPs)
  region        = var.region

  # GKE VPC-native 클러스터에 필요한 Pod/Service 보조 IP 대역
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.1.0.0/16" # Pod용 (65,536 IPs)
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.2.0.0/20" # Service용 (4,096 IPs)
  }
}
