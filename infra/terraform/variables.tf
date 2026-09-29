variable "project_id" {
  description = "GCP 프로젝트 ID"
  type        = string
}

variable "region" {
  description = "GCP 리전"
  type        = string
  default     = "asia-northeast3" # 서울
}

variable "environment" {
  description = "배포 환경 (dev, prod)"
  type        = string
  default     = "dev"
}

variable "cluster_name" {
  description = "GKE 클러스터 이름"
  type        = string
  default     = "auction-cluster"
}
