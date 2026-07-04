provider "aws" {
  region = var.aws_region
}

# RDS 인스턴스 — Operator(K8s)나 Ansible(VM)과 같은 자리에서, 클라우드는 Terraform이 만든다.
resource "aws_db_instance" "this" {
  identifier          = var.instance_name
  engine              = var.engine
  engine_version      = var.engine == "postgres" ? "16" : "8.0"
  instance_class      = "db.t3.micro"
  allocated_storage   = 20
  db_name             = var.db_name
  username            = var.master_username
  password            = var.master_password
  skip_final_snapshot = true
  publicly_accessible = false
  # 실제 운영에서는 subnet_group·security_group·parameter_group·multi_az 등을 추가한다(데모는 최소).
}

# 생성 후 DBTower에 멱등 등록(PUT) — "생성과 관제를 잇는다".
# apply 시점에 RDS 엔드포인트를 읽어 관제탑에 등록한다. (이 저장소에서는 apply를 실행하지 않았다)
resource "terraform_data" "register" {
  triggers_replace = [aws_db_instance.this.endpoint]

  provisioner "local-exec" {
    command = <<-CMD
      curl -sf -X PUT "${var.dbtower_url}/api/instances" \
        -H "Authorization: Bearer ${var.dbtower_token}" \
        -H "Content-Type: application/json" \
        -d '{
          "name": "${var.instance_name}",
          "type": "${upper(var.engine) == "POSTGRES" ? "POSTGRESQL" : "MYSQL"}",
          "host": "${aws_db_instance.this.address}",
          "port": ${aws_db_instance.this.port},
          "dbName": "${var.db_name}",
          "username": "${var.monitor_username}",
          "password": "${var.monitor_password}"
        }'
    CMD
  }
}
