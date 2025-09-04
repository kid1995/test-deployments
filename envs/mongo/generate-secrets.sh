#!/bin/bash

#
# Erzeugt die Dateien `my-cluster-name-secrets.yaml` und `mongouser-secret.yaml`.
#
# Benötigt `kubectl` und `kubeseal`.
#
# Datei `public-key-nop` muss im aktuellen Verzeichnis liegen.
#

NAMESPACE=elpa-elpa4

echo "MongoDB Backup password:"
read BACKUP_PW

echo "MongoDB Database Admin password:"
read DB_ADMIN_PW

echo "MongoDB Cluster Admin password:"
read CLUSTER_ADMIN_PW

echo "MongoDB Cluster Monitor password:"
read CLUSTER_MONITOR_PW

echo "MongoDB mongouser password:"
read MONGOUSER_PW

kubectl create secret generic my-cluster-b-secrets \
    --from-literal=MONGODB_BACKUP_USER=backup \
    --from-literal=MONGODB_BACKUP_PASSWORD=$BACKUP_PW \
    --from-literal=MONGODB_DATABASE_ADMIN_USER=databaseAdmin \
    --from-literal=MONGODB_DATABASE_ADMIN_PASSWORD=$DB_ADMIN_PW \
    --from-literal=MONGODB_CLUSTER_ADMIN_USER=clusterAdmin \
    --from-literal=MONGODB_CLUSTER_ADMIN_PASSWORD=$CLUSTER_ADMIN_PW \
    --from-literal=MONGODB_CLUSTER_MONITOR_USER=clusterMonitor \
    --from-literal=MONGODB_CLUSTER_MONITOR_PASSWORD=$CLUSTER_MONITOR_PW \
    --from-literal=MONGODB_USER_ADMIN_USER=userAdmin \
    --from-literal=PMM_SERVER_API_KEY=apikey \
    -n $NAMESPACE \
    --dry-run=client \
    -o yaml \
    | kubeseal --format yaml --cert=public-key-nop > my-cluster-b-secrets.yaml

# prefix will be added before mongouser-secret
kubectl create secret generic dev-b-mongouser-secret \
    --from-literal=PASSWORD=$MONGOUSER_PW \
    -n $NAMESPACE \
    --dry-run=client \
    -o yaml \
    | kubeseal --format yaml --cert=public-key-nop > dev-b-mongouser-secret.yaml