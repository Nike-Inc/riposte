#!/usr/bin/env bash

# Taken and modified for ubuntu from
# https://github.com/hudl/fargo/blob/master/provision_eureka.sh

EUREKA_BIN="https://netflixoss.ci.cloudbees.com/job/eureka-master/lastSuccessfulBuild/artifact/*zip*/archive.zip"

sysctl -w net.ipv6.conf.all.disable_ipv6=1

apt-get update
sleep 10

apt-get install -y unzip tomcat7 htop vim

echo "127.0.0.1   localhost localhost.localdomain localhost4 localhost4.localdomain4" > /etc/hosts
echo "<?xml version='1.0' encoding='utf-8'?>
<tomcat-users>
  <user username=\"tomcatuser\" password=\"somep4ss\" roles=\"manager,admin,manager-gui,manager-status,manager-script,manager-jmx,admin-gui,admin-script\"/>
</tomcat-users>" > /etc/tomcat7/tomcat-users.xml
chown tomcat7:tomcat7 /etc/tomcat7/tomcat-users.xml
chmod 644 /etc/tomcat7/tomcat-users.xml

curl -s -o /tmp/eureka_archive.zip $EUREKA_BIN
mkdir /tmp/eureka_archive
unzip -o -d /tmp/eureka_archive /tmp/eureka_archive.zip
war=$(find /tmp/eureka_archive -name "*war")
unzip -o -d /var/lib/tomcat7/webapps/eureka $war
rm -rf /tmp/eureka_archive*

cp /vagrant/src/vagrant/*.properties /var/lib/tomcat7/webapps/eureka/WEB-INF/classes/

chown -R tomcat7:tomcat7 /var/lib/tomcat7/webapps/eureka

service tomcat7 restart