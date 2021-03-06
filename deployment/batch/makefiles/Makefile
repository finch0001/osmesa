include config-aws.mk # Vars related to AWS credentials and services used
include config-emr.mk # Vars related to type and size of EMR cluster
include config-run.mk # Vars related to ingest step and spark parameters

ZOOM ?= 15

APPS_SRC_DIR := ../../../src/apps
APPS_JAR := osmesa-apps.jar
APPS_ASSEMBLY := ${APPS_SRC_DIR}/target/scala-2.11/${APPS_JAR}

ifeq ($(USE_SPOT),true)
MASTER_BID_PRICE:=BidPrice=${MASTER_PRICE},
WORKER_BID_PRICE:=BidPrice=${WORKER_PRICE},
endif

ifdef COLOR
COLOR_TAG=--tags Color=${COLOR}
endif

ifndef CLUSTER_ID
CLUSTER_ID=$(shell if [ -e "cluster-id.txt" ]; then cat cluster-id.txt; fi)
endif

ifndef CORE_EMR_ATTRS
EMR_ATTRS_CORE=
else
EMR_ATTRS_CORE=,${CORE_EMR_ATTRS}
endif

ifndef MASTER_EMR_ATTRS
EMR_ATTRS_MASTER=
else
EMR_ATTRS_MASTER=,${MASTER_EMR_ATTRS}
endif

rwildcard=$(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))

${APPS_ASSEMBLY}: $(call rwildcard, ${APPS_SRC_DIR}/src, *.scala) ${APPS_SRC_DIR}/build.sbt
	cd ${APPS_SRC_DIR}/.. && ./sbt "project apps" assembly
	@touch -m ${APPS_ASSEMBLY}

create-cluster:
	aws emr create-cluster --name "${NAME}" ${COLOR_TAG} \
--release-label emr-5.19.0 \
--output text \
--use-default-roles \
--configurations "file://$(CURDIR)/scripts/configurations.json" \
--log-uri ${S3_URI}/logs \
--ec2-attributes KeyName=${EC2_KEY},SubnetId=${SUBNET_ID},EmrManagedMasterSecurityGroup=${MASTER_SECURITY_GROUP},EmrManagedSlaveSecurityGroup=${WORKER_SECURITY_GROUP},ServiceAccessSecurityGroup=${SERVICE_ACCESS_SG},AdditionalMasterSecurityGroups=${SANDBOX_SG},AdditionalSlaveSecurityGroups=${SANDBOX_SG} \
--applications Name=Ganglia Name=Hadoop Name=Hue Name=Spark Name=Zeppelin \
--instance-groups \
'Name=Master,${MASTER_BID_PRICE}InstanceCount=1,InstanceGroupType=MASTER,InstanceType=${MASTER_INSTANCE}${EMR_ATTRS_MASTER}' \
'Name=Workers,${WORKER_BID_PRICE}InstanceCount=${WORKER_COUNT},InstanceGroupType=CORE,InstanceType=${WORKER_INSTANCE}${EMR_ATTRS_CORE}' \
| tee cluster-id.txt

upload-apps: ${APPS_ASSEMBLY}
	@aws s3 cp ${APPS_ASSEMBLY} ${S3_URI}/

ssh:
	@aws emr ssh --cluster-id $(CLUSTER_ID) --key-pair-file "${PEM_FILE}"

proxy:
	aws emr socks --cluster-id ${CLUSTER_ID} --key-pair-file "${PEM_FILE}"

terminate-cluster:
	aws emr terminate-clusters --cluster-ids ${CLUSTER_ID}
	rm -f cluster-id.txt
	rm -f last-step-id.txt

get-logs:
	@aws emr ssh --cluster-id $(CLUSTER_ID) --key-pair-file "${HOME}/${EC2_KEY}.pem" \
		--command "rm -rf /tmp/spark-logs && hdfs dfs -copyToLocal /var/log/spark/apps /tmp/spark-logs"
	@mkdir -p  logs/$(CLUSTER_ID)
	@aws emr get --cluster-id $(CLUSTER_ID) --key-pair-file "${HOME}/${EC2_KEY}.pem" --src "/tmp/spark-logs/" --dest logs/$(CLUSTER_ID)

.PHONY: get-logs
