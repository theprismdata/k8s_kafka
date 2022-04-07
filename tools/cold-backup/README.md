## Strimzi backup

Bash script for offline (_cold_) backups of Kafka clusters on Kubernetes or OpenShift. 
The script only supports a local file system, and you need to make sure to have enough free disk space in the target directory.

If you think you don't need a backup strategy for Kafka because of its embedded data replication, then consider the impact of a misconfiguration, bug, or security breach that deletes all of your data. 
For online (_hot_) backups, you can use storage snapshotting or stream into object storage.

Run the script as a Kubernetes user with permission to work with PVC and Strimzi custom resources. 

The procedure will stop the Cluster Operator and selected cluster for the duration of the backup. 
Before restoring the Kafka cluster you need to make sure to have the right version of Strimzi CRDs installed. 
If you have a single cluster-wide Cluster Operator, then you need to scale it down manually. 
You can run backup and restore procedures for different Kafka clusters in parallel. 
Consumer group offsets are included, but not Kafka Connect, MirrorMaker and Kafka Bridge custom resources.

## Requirements

- bash 5+ (GNU)
- tar 1.33+ (GNU)
- kubectl 1.16+ (K8s CLI)
- yq 4.6+ (YAML processor)
- enough disk space

## Usage example

```sh
# deploy a test cluster
kubectl create namespace myproject
kubectl config set-context --current --namespace="myproject"
kubectl create -f ./install/cluster-operator
kubectl create -f ./examples/kafka/kafka-persistent.yaml
kubectl create -f ./examples/topic/kafka-topic.yaml
CLIENT_IMAGE="quay.io/strimzi/kafka:latest-kafka-3.1.0"

# send 100000 messages and consume them
kubectl run producer-perf -it --image="$CLIENT_IMAGE" --rm="true" --restart="Never" -- \
  bin/kafka-producer-perf-test.sh --topic my-topic --record-size 1000 --num-records 100000 \
    --throughput -1 --producer-props acks=1 bootstrap.servers="my-cluster-kafka-bootstrap:9092"

kubectl run consumer -it --image="$CLIENT_IMAGE" --rm="true" --restart="Never" -- \
  bin/kafka-console-consumer.sh --bootstrap-server my-cluster-kafka-bootstrap:9092 \
    --topic my-topic --group my-group --from-beginning --timeout-ms 15000

# save consumer group offsets    
kubectl run consumer-groups -it --image="$CLIENT_IMAGE" --rm="true" --restart="Never" -- \
  bin/kafka-consumer-groups.sh --bootstrap-server my-cluster-kafka-bootstrap:9092 \
    --group my-group --describe

# send additional 12345 messages
kubectl run producer-perf -it --image="$CLIENT_IMAGE" --rm="true" --restart="Never" -- \
  bin/kafka-producer-perf-test.sh --topic my-topic --record-size 1000 --num-records 12345 \
    --throughput -1 --producer-props acks=1 bootstrap.servers="my-cluster-kafka-bootstrap:9092"

# run backup procedure
./tools/cold-backup/run.sh backup -n myproject -c my-cluster -t /tmp/my-cluster.tgz

# recreate the namespace and deploy the operator
kubectl delete ns myproject
kubectl create ns myproject
kubectl create -f ./install/cluster-operator

# run restore procedure and wait for provisioning
./tools/cold-backup/run.sh restore -n myproject -c my-cluster -s /tmp/my-cluster.tgz

# check consumer group offsets (expected: current-offset match)
kubectl run consumer-groups -it --image="$CLIENT_IMAGE" --rm="true" --restart="Never" -- \
  bin/kafka-consumer-groups.sh --bootstrap-server my-cluster-kafka-bootstrap:9092 \
  --group my-group --describe

# check consumer group recovery (expected: 12345)
kubectl run consumer -it --image="$CLIENT_IMAGE" --rm="true" --restart="Never" -- \
  bin/kafka-console-consumer.sh --bootstrap-server my-cluster-kafka-bootstrap:9092 \
  --topic my-topic --group my-group --from-beginning --timeout-ms 15000

# check total number of messages (expected: 112345)
kubectl run consumer -it --image="$CLIENT_IMAGE" --rm="true" --restart="Never" -- \
  bin/kafka-console-consumer.sh --bootstrap-server my-cluster-kafka-bootstrap:9092 \
  --topic my-topic --group new-group --from-beginning --timeout-ms 15000
```
