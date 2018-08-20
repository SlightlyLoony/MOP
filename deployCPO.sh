#! /bin/bash
scp out/artifacts/CPO.jar tom@beast:/apps/cpo
scp CPO.service tom@beast:/apps/cpo
scp CPOConfigTom.json tom@beast:/apps/cpo
scp CPOLog.json tom@beast:/apps/cpo
scp runCPO.sh tom@beast:/apps/cpo
ssh tom@beast << ENDSSH
cd /apps/cpo
sudo chown cpo:cpo CPO.jar
sudo chmod ug+xrw CPO.jar
sudo chown cpo:cpo CPOConfigTom.json
sudo chmod ug+xrw CPOConfigTom.json
sudo chown cpo:cpo CPOLog.json
sudo chmod ug+xrw CPOLog.json
sudo chown cpo:cpo runCPO.sh
sudo chmod ug+xrw runCPO.sh
sudo cp -u CPO.service /etc/systemd/system
sudo chown cpo:cpo /etc/systemd/system/CPO.service
sudo chmod ug+xrw /etc/systemd/system/CPO.service
ENDSSH
