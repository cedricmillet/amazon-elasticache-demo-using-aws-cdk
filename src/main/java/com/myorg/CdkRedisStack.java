package com.myorg;

import software.constructs.Construct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.elasticache.*;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.MySqlInstanceEngineProps;
import software.amazon.awscdk.services.rds.MysqlEngineVersion;

public class CdkRedisStack extends Stack {

    public CdkRedisStack(final Construct scope, final String id) throws IOException {
        this(scope, id, null);
    }

    public CdkRedisStack(final Construct scope, final String id, final StackProps props) throws IOException {
        super(scope, id, props);

        // Adapted from https://github.com/aws-samples/amazon-elasticache-demo-using-aws-cdk/blob/main/elasticache_demo_cdk_app/elasticache_demo_cdk_app_stack.py

        //  Create VPC
        final Vpc vpc = Vpc.Builder.create(this, "VPC")
        .natGateways(1)
        .ipAddresses(IpAddresses.cidr("10.0.0.0/16"))
        .subnetConfiguration(Arrays.asList(
            SubnetConfiguration.builder().name("public").subnetType(SubnetType.PUBLIC).cidrMask(24).build(),
            SubnetConfiguration.builder().name("private").subnetType(SubnetType.PRIVATE_WITH_EGRESS).cidrMask(24).build()
        ))
        .build();

        //  Create DB security group
        final SecurityGroup dbSG = SecurityGroup.Builder.create(this, "db-sec-group")
        .securityGroupName("db-sec-group").vpc(vpc).allowAllOutbound(true).build();

        //  Create webserver security group
        final SecurityGroup webSG = SecurityGroup.Builder.create(this, "web-sec-group")
        .securityGroupName("web-sec-group").vpc(vpc).allowAllOutbound(true).build();

        //  Create redis security group
        final SecurityGroup redisSG = SecurityGroup.Builder.create(this, "redis-sec-group")
        .securityGroupName("redis-sec-group").vpc(vpc).allowAllOutbound(true).build();
        
        //  Create redis subnet group
        final List<String> privateSubnetIds = vpc.getPrivateSubnets().stream().map(sb -> sb.getSubnetId()).collect(Collectors.toList());
        final CfnSubnetGroup redisSubnetGroup = CfnSubnetGroup.Builder.create(this, "redis_subnet_group")
        .subnetIds(privateSubnetIds)
        .description("subnet group for redis")
        .build();

        //  Add ingress rules to security group :
        //  Allow connection from 0.0.0/0 to webserver
        webSG.addIngressRule(Peer.ipv4("0.0.0.0/0"), Port.tcp(8008), "flask application"); // app port
        //  Allow connection from webserver to db
        dbSG.addIngressRule(webSG, Port.tcp(3306), "Allow MySQL connection");
        //  Allow connection from webserver to redis
        redisSG.addIngressRule(webSG, Port.tcp(6379), "Allow Redis connection");

        //  Create RDS MySQL Database
        final DatabaseInstance rdsInstance = DatabaseInstance.Builder.create(this, "test")
        .databaseName("db name")
        .engine(DatabaseInstanceEngine.mysql(MySqlInstanceEngineProps.builder().version(MysqlEngineVersion.VER_8_0_28).build()))
        .vpc(vpc)
        .port(3306)
        .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MEDIUM))
        .removalPolicy(RemovalPolicy.DESTROY)
        .deletionProtection(false)
        .iamAuthentication(true)
        .securityGroups(Arrays.asList(dbSG))
        .storageEncrypted(true)
        .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_WITH_EGRESS).build())
        .build();

        //  Create Redis Cluster
        final CfnCacheCluster redisCluster = CfnCacheCluster.Builder.create(this, "redis_cluster")
        .engine("redis")                        // memcached / redis
        .cacheNodeType("cache.t3.small")
        .numCacheNodes(1)
        .cacheSubnetGroupName(redisSubnetGroup.getRef())
        .vpcSecurityGroupIds(Arrays.asList(redisSG.getSecurityGroupId()))
        .build();

        //  AMI Definition : latest amazon linux image
        final IMachineImage amzLinuxImage = MachineImage.latestAmazonLinux2(AmazonLinux2ImageSsmParameterProps.builder()
        .edition(AmazonLinuxEdition.STANDARD)
        .virtualization(AmazonLinuxVirt.HVM)
        .storage(AmazonLinuxStorage.GENERAL_PURPOSE).build());

        // The following inline policy makes sure we allow only retrieving the secret value, provided the secret is already known. 
        // It does not allow listing of all secrets.
        final Role role = Role.Builder.create(this, "ElasticacheDemoInstancePolicy").assumedBy(new ServicePrincipal("ec2.amazonaws.com")).build();
        role.addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"));
        role.addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSCloudFormationReadOnlyAccess"));
        role.attachInlinePolicy(Policy.Builder.create(this, "secret-read-only").statements(Arrays.asList(
            PolicyStatement.Builder.create()
            .actions(Arrays.asList("secretsmanager:GetSecretValue"))
            .resources(Arrays.asList("arn:aws:secretsmanager:*"))
            .effect(Effect.ALLOW)
            .build()
        )).build());

        // Read EC2 Instance userData
        final String instanceUserData = new String(Files.readAllBytes(Paths.get("./userdata.sh")));

        // EC2 Instance for Web Server
        final Instance ec2Instance = Instance.Builder.create(this, "Webserver")
        .instanceType(InstanceType.of(InstanceClass.T3, InstanceSize.SMALL))
        .machineImage(amzLinuxImage)
        .vpc(vpc)
        .role(role)
        .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
        .securityGroup(webSG)
        .userData(UserData.custom(instanceUserData))
        .build();

        // Generate cloudformation outputs
        CfnOutput.Builder.create(this, "secret_name").value(rdsInstance.getSecret().getSecretName()).build();
        CfnOutput.Builder.create(this, "mysql_endpoint").value(rdsInstance.getDbInstanceEndpointAddress()).build();
        CfnOutput.Builder.create(this, "redis_endpoint").value(redisCluster.getAttrRedisEndpointAddress()).build();
        CfnOutput.Builder.create(this, "webserver_public_ip").value(ec2Instance.getInstancePublicIp()).build();
        CfnOutput.Builder.create(this, "webserver_public_url").value(ec2Instance.getInstancePublicDnsName()).build();
    }
}
