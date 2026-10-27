# The deploy half of the estate pipeline (.github/workflows/deploy.yml): a CodeDeploy application and deployment group for service A, whose
# ECS service has the CODE_DEPLOY controller (stack.tf). Floci's CodeDeploy takes the target group names and the listener ARN as given, so
# they are plain names here; on real AWS they would be an application load balancer's. Only this root has it: a CODE_DEPLOY service on real AWS
# needs a load balancer, which costs money per hour (terraform/aws never has the service).
resource "aws_iam_role" "codedeploy" {
  name = "estate-codedeploy"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "codedeploy.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "codedeploy" {
  role       = aws_iam_role.codedeploy.name
  policy_arn = "arn:aws:iam::aws:policy/AWSCodeDeployRoleForECS"
}

resource "aws_codedeploy_app" "estate" {
  name             = "estate"
  compute_platform = "ECS"
}

resource "aws_codedeploy_deployment_group" "service_a" {
  app_name               = aws_codedeploy_app.estate.name
  deployment_group_name  = "service-a"
  service_role_arn       = aws_iam_role.codedeploy.arn
  deployment_config_name = "CodeDeployDefault.ECSAllAtOnce"

  deployment_style {
    deployment_type   = "BLUE_GREEN"
    deployment_option = "WITH_TRAFFIC_CONTROL"
  }

  blue_green_deployment_config {
    deployment_ready_option {
      action_on_timeout = "CONTINUE_DEPLOYMENT"
    }
    terminate_blue_instances_on_deployment_success {
      action                           = "TERMINATE"
      termination_wait_time_in_minutes = 0
    }
  }

  ecs_service {
    cluster_name = aws_ecs_cluster.estate.name
    service_name = module.stack.ecs_service
  }

  load_balancer_info {
    target_group_pair_info {
      prod_traffic_route {
        listener_arns = ["arn:aws:elasticloadbalancing:eu-central-1:000000000000:listener/app/estate/1/2"]
      }
      target_group { name = "estate-blue" }
      target_group { name = "estate-green" }
    }
  }
}

output "codedeploy_app" { value = aws_codedeploy_app.estate.name }
output "codedeploy_group" { value = aws_codedeploy_deployment_group.service_a.deployment_group_name }
