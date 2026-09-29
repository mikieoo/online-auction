resource "google_artifact_registry_repository" "auction" {
  repository_id = "auction-platform"
  location      = var.region
  format        = "DOCKER"
  description   = "경매 시스템 Docker 이미지 저장소"

  # 오래된 이미지 자동 정리 (30일 이상, 태그 없는 이미지)
  cleanup_policies {
    id     = "delete-untagged"
    action = "DELETE"
    condition {
      tag_state = "UNTAGGED"
      older_than = "2592000s" # 30일
    }
  }

  labels = local.common_labels
}
