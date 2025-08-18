#!/bin/bash

#
# Erzeugt die Dateien `my-cluster-name-secrets.yaml` und `mongouser-secret.yaml`.
#
# Benötigt `kubectl` und `kubeseal`.
#
# Datei `public-key-nop` muss im aktuellen Verzeichnis liegen.
#

NAMESPACE=elpa4

echo "MongoDB Backup password:"
read BACKUP_PW

echo "MongoDB Database Admin password:"
read DB_ADMIN_PW

echo "MongoDB Cluster Admin password:"
read CLUSTER_ADMIN_PW

echo "MongoDB Cluster Monitor password:"
read CLUSTER_MONITOR_PW

#
# my-cluster-name-secrets
#

kubectl create secret generic my-cluster-name-secrets \
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
    | kubeseal --format yaml --cert=public-key-nop > my-cluster-name-secrets.yaml

#
# mongouser-secret
#

echo "MongoDB mongouser password:"
read MONGOUSER_PW

kubectl create secret generic db1-mongouser-secret \
    --from-literal=PASSWORD=$MONGOUSER_PW \
    -n $NAMESPACE \
    --dry-run=client \
    -o yaml \
    | kubeseal --format yaml --cert=public-key-nop > mongouser-secret.yaml

#
# postgresuser-secrets
#

echo "PostgreSQL user password:"
read POSTGRESUSER_PW

kubectl create secret generic postgresuser-secret \
    --from-literal=PASSWORD=$POSTGRESUSER_PW \
    -n $NAMESPACE \
    --dry-run=client \
    -o yaml \
    | kubeseal --format yaml --cert=public-key-nop > postgresuser-secret.yaml
