# The real running estate (switch `run_services` in stack.tf, default off): what service A and service B need to run as Fargate services on a real
# account without anything that costs money by the hour beyond the tasks themselves: ECR repositories for the two images, a security group,
# a Cloud Map private DNS namespace (B reaches A as service-a.<prefix>.local), the public IP of default-VPC subnets instead of a NAT gateway.
# The task role, the execution policy, the Postgres sidecar and B's ECS service are in modules/stack (variable real_tasks).

resource "aws_ecr_repository" "service" {
  for_each             = var.run_services ? toset(["a", "b"]) : toset([])
  name                 = "${var.prefix}/service-${each.key}"
  image_tag_mutability = "MUTABLE"
  force_delete         = true # destroy removes the images with the repository
}

resource "aws_security_group" "services" {
  count       = var.run_services ? 1 : 0
  name        = "${var.prefix}-services"
  description = "Services A and B of the estate: ports 8081 and 8082 from the VPC and from allowed_cidrs"
  vpc_id      = data.aws_vpc.default[0].id

  ingress {
    from_port   = 8081
    to_port     = 8082
    protocol    = "tcp"
    cidr_blocks = concat([data.aws_vpc.default[0].cidr_block], var.allowed_cidrs)
  }
  # Postgres (B's sidecar) for the Deliberator `sql` step from the allowed addresses only; B reaches it on localhost inside its task
  dynamic "ingress" {
    for_each = length(var.allowed_cidrs) == 0 ? [] : [1]
    content {
      from_port   = 5432
      to_port     = 5432
      protocol    = "tcp"
      cidr_blocks = var.allowed_cidrs
    }
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_service_discovery_private_dns_namespace" "estate" {
  count = var.run_services ? 1 : 0
  name  = "${var.prefix}.local"
  vpc   = data.aws_vpc.default[0].id
}

resource "aws_service_discovery_service" "a" {
  count = var.run_services ? 1 : 0
  name  = "service-a"
  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.estate[0].id
    dns_records {
      ttl  = 10
      type = "A"
    }
    routing_policy = "MULTIVALUE"
  }
}
