# Wireless Battery Monitor · Java Edition

O Wireless Battery Monitor mostra a carga dos seus periféricos na janela principal, na bandeja do sistema e ao lado do relógio do Windows. A versão **1.0.0** foi desenvolvida para Windows x64 e testada no Windows 11.

![Janela do monitor no tema escuro com a porcentagem de bateria do mouse, do headset e do controle](docs/screenshot.png)

## Dispositivos suportados

**Esta versão Java oferece suporte exclusivamente aos três dispositivos abaixo, conectados pelo respectivo dongle USB de 2,4 GHz.**

| Dispositivo | Tipo | Conexão | Estado do suporte |
| --- | --- | --- | --- |
| Akko Verge S9 / S9 Ultra | Headset sem fio | Dongle USB 2,4 GHz | Implementado e testado no Windows 11 |
| Delux M800 Pro PAW3395 | Mouse sem fio | Dongle USB 2,4 GHz | Implementado e testado no Windows 11 |
| GameSir Cyclone 2 | Controle sem fio | Dongle USB 2,4 GHz, modo XInput | Implementado e testado no Windows 11 |

Os testes de compatibilidade abrangem esses modelos específicos. Outras versões, firmwares ou revisões de hardware podem usar relatórios HID diferentes, mesmo quando o nome comercial é semelhante.

## Funcionalidades

- O aplicativo detecta automaticamente os receptores compatíveis e consulta a bateria de cada dispositivo. A janela em Java Swing com FlatLaf mostra a porcentagem e o estado de conexão em cartões individuais.
- As porcentagens também podem aparecer perto do relógio do Windows ou em ícones na bandeja do sistema. O posicionamento do widget depende da configuração da barra de tarefas e da versão do Windows.
- A inicialização com o Windows é opcional e foi confirmada após reiniciar um computador com Windows 11. É possível iniciar o aplicativo minimizado.
- As preferências de tema claro ou escuro, exibição e intervalo de atualização ficam salvas entre sessões.
- O diagnóstico HID permite inspecionar interfaces e relatórios dos dispositivos. O modo de simulação permite experimentar a interface com valores fictícios.

## Instalação

Quando a versão 1.0.0 for publicada, você poderá baixar um dos dois pacotes para **Windows x64** na página de [GitHub Releases](https://github.com/kybiy/Wireless-Device-Battery-Monitor/releases).

| Arquivo | Como usar |
| --- | --- |
| `WirelessBattery-1.0.0.exe` | Execute o instalador e siga o assistente. O instalador inclui um runtime Java compatível. |
| `WirelessBattery-1.0.0-portable-win-x64.zip` | Extraia a pasta inteira e execute `WirelessBattery\WirelessBattery.exe`. O pacote portátil inclui o runtime e dispensa instalação. |

Conecte o dongle do periférico e ligue o dispositivo. Para o GameSir Cyclone 2, selecione o modo XInput.

### Integridade e Windows SmartScreen

Na pasta do download, execute no PowerShell o comando correspondente ao arquivo escolhido e compare o resultado com a entrada do mesmo nome em `SHA256SUMS.txt` da Release.

```powershell
Get-FileHash .\WirelessBattery-1.0.0.exe -Algorithm SHA256
Get-FileHash .\WirelessBattery-1.0.0-portable-win-x64.zip -Algorithm SHA256
```

O Windows SmartScreen pode exibir um aviso para executáveis sem assinatura digital. Confira a origem do download e o hash antes de prosseguir. Se o hash divergir, descarte o arquivo.

## Créditos e projeto original

This is a Java recode of krisgfx9/Wireless-Device-Battery-Monitor (the original C++ BatteryHub by krisgfx9), adapted to support three specific wireless devices.

Este projeto é uma reimplementação em Java inspirada no Wireless-Device-Battery-Monitor, originalmente desenvolvido em C++ por krisgfx9. Todos os créditos pela criação do projeto original pertencem ao seu respectivo autor.

| Projeto de referência | Contribuição como referência | Licença do projeto consultado |
| --- | --- | --- |
| [krisgfx9/Wireless-Device-Battery-Monitor](https://github.com/krisgfx9/Wireless-Device-Battery-Monitor) | Projeto C++ original, conceito e referências de integração com a barra de tarefas e protocolos. | [GPL-3.0 ou posterior, conforme o README histórico](https://github.com/ferloom/Wireless-Device-Battery-Monitor/blob/f42a85f9b53ccb6d63aad0dd11a64787d71782ac/README.md#license) |
| [joaoarebalo/Wireless-Device-Battery-Monitor-Fallen](https://github.com/joaoarebalo/Wireless-Device-Battery-Monitor-Fallen) | Fork consultado durante o desenvolvimento. | [GPL-3.0 ou posterior](https://github.com/joaoarebalo/Wireless-Device-Battery-Monitor-Fallen#license) |
| [xb-bx/m800-pro-driver](https://github.com/xb-bx/m800-pro-driver) | Formato da consulta e posição dos campos de bateria do Delux M800 Pro PAW3395. | [GPL-2.0](https://github.com/xb-bx/m800-pro-driver/blob/master/LICENSE) |
| [vdemonchy/cyclone2-linux](https://github.com/vdemonchy/cyclone2-linux) | Referência para o protocolo do GameSir Cyclone 2. | [GPL-3.0](https://github.com/vdemonchy/cyclone2-linux/blob/HEAD/LICENSE) |
| [pedrosegato/gamesir-reverse-engineering](https://github.com/pedrosegato/gamesir-reverse-engineering) | Documentação do protocolo citada na implementação do GameSir. | [MIT](docs/licenses/gamesir-reverse-engineering-LICENSE.txt) |

A reimplementação Java é distribuída sob a [GNU General Public License v3.0](LICENSE). Os ícones SVG incluem desenhos de Lucide e Feather. Os avisos de copyright e as permissões de uso estão na [licença dos ícones](docs/licenses/lucide-LICENSE.txt).

## Informações técnicas

O projeto utiliza Java 17, Swing e FlatLaf (Apache-2.0), JSVG (MIT), hid4java (MIT) com HIDAPI e JNA (Apache-2.0 ou LGPL-2.1 ou posterior). JUnit 5 (EPL-2.0) é usado nos testes. Os JARs de FlatLaf, FlatLaf Extras, JSVG e JNA incluem suas licenças em `META-INF/LICENSE`. Consulte também os avisos de [hid4java](docs/licenses/hid4java-LICENSE.txt) e [HIDAPI](docs/licenses/hidapi-LICENSE.txt), incluindo a [opção BSD](docs/licenses/hidapi-LICENSE-bsd.txt).

As preferências ficam em `%USERPROFILE%\.wireless-battery-java\settings.properties`. A inicialização automática utiliza a chave `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` do usuário atual.

### Compilar, testar e executar

Com JDK 17 ou superior e Apache Maven, execute na raiz do projeto. O primeiro comando compila o aplicativo, executa os testes e gera o JAR. O segundo abre o monitor.

```powershell
mvn clean package
mvn exec:java
```

### Empacotar distribuição Windows (`app-image` e `.exe` com `jpackage`)

Após executar `mvn clean package` (que gera `target/wireless-battery-java-1.0.0.jar` e copia as dependências de runtime para `target/jpackage-input`), copie o JAR principal para `target/jpackage-input` e execute o `jpackage` (requer **WiX Toolset 3.x** no `PATH` para o instalador `.exe`):

```powershell
Copy-Item "target/wireless-battery-java-1.0.0.jar" -Destination "target/jpackage-input/" -Force

jpackage --type app-image `
  --name "WirelessBattery" `
  --app-version "1.0.0" `
  --vendor "Henrique" `
  --description "Wireless Device Battery Monitor for Windows 11 (Delux M800 Pro, Akko S9 Ultra, GameSir Cyclone 2)" `
  --input "target/jpackage-input" `
  --main-jar "wireless-battery-java-1.0.0.jar" `
  --main-class "br.com.wirelessbattery.Main" `
  --icon "src/main/resources/icons/app-icon.ico" `
  --dest "dist/app-image" `
  --java-options "-Dfile.encoding=UTF-8" `
  --java-options "--enable-native-access=ALL-UNNAMED"

jpackage --type exe `
  --name "WirelessBattery" `
  --app-version "1.0.0" `
  --vendor "Henrique" `
  --description "Wireless Device Battery Monitor for Windows 11 (Delux M800 Pro, Akko S9 Ultra, GameSir Cyclone 2)" `
  --app-image "dist/app-image/WirelessBattery" `
  --icon "src/main/resources/icons/app-icon.ico" `
  --dest "dist" `
  --win-dir-chooser `
  --win-menu `
  --win-menu-group "Wireless Battery Monitor" `
  --win-shortcut `
  --win-shortcut-prompt `
  --win-per-user-install `
  --win-upgrade-uuid "7c2f4b5e-3d9a-4e8c-91f2-8a6b4d2e1c90"
```
