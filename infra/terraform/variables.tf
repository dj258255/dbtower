variable "aws_region" {
  description = "RDS를 만들 AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "instance_name" {
  description = "RDS 식별자 겸 DBTower 등록 이름"
  type        = string
  default     = "prod-orders"
}

variable "engine" {
  description = "RDS 엔진 (mysql | postgres)"
  type        = string
  default     = "postgres"
}

variable "db_name" {
  description = "생성할 데이터베이스 이름"
  type        = string
  default     = "orders"
}

variable "master_username" {
  type    = string
  default = "dbadmin"
}

variable "master_password" {
  description = "RDS 마스터 비밀번호 — tfvars/환경변수로만 주입(커밋 금지)"
  type        = string
  sensitive   = true
}

variable "monitor_username" {
  description = "DBTower가 붙을 모니터링 계정 (RDS 생성 후 별도 부여 전제)"
  type        = string
  default     = "dbtower_monitor"
}

variable "monitor_password" {
  type      = string
  sensitive = true
}

variable "dbtower_url" {
  description = "관제탑 주소"
  type        = string
  default     = "http://localhost:8080"
}

variable "dbtower_token" {
  description = "DBTower ADMIN Bearer 토큰"
  type        = string
  sensitive   = true
}
