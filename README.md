# Wireless Battery Monitor · Java Edition

O Wireless Battery Monitor mostra a carga dos seus periféricos na janela principal, na bandeja do sistema e ao lado do relógio do Windows. A versão **1.1.0** foi desenvolvida para Windows x64 e testada no Windows 11.

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

- **Monitoramento automático de bateria:** detecta automaticamente os receptores compatíveis e consulta a bateria de cada dispositivo via USB HID (somente leitura). A janela em Java Swing com FlatLaf exibe a porcentagem e o estado de conexão em cartões individuais, preservando a última leitura conhecida em repouso.
- **Widget nativo Win32 na barra de tarefas (compatível com `Win+D`):** exibe os indicadores horizontais ao lado do relógio do Windows utilizando uma janela filha nativa (`WS_CHILD | WS_CLIPSIBLINGS`, `WS_EX_LAYERED | WS_EX_NOACTIVATE`) anexada à `Shell_TrayWnd` e renderizada via `UpdateLayeredWindow`. Por fazer parte da hierarquia da barra de tarefas, o indicador **permanece visível ao usar `Win+D` (*Mostrar área de trabalho*) ou `Alt+Tab`**, não rouba foco, não sobrepõe jogos em tela cheia e se recupera automaticamente caso o `explorer.exe` seja reiniciado.
- **Integração com a bandeja do sistema (`SystemTray`):** ícones individuais nítidos por dispositivo com menu de contexto completo, podendo operar isoladamente ou em conjunto com o widget da barra de tarefas.
- **Otimizações de memória em segundo plano:** cache concorrente de ícones vetoriais SVG (`SVG_CACHE`), pausa automática de animações quando a janela está minimizada, reaproveitamento de cartões sem reconstrução desnecessária de layout e limite fixo de histórico na aba de logs.
- **Inicialização com o Windows 11 e modo minimizado:** registro opcional em `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` (sem exigir privilégios de administrador) e suporte a iniciar diretamente na bandeja/barra de tarefas (`--minimized`).
- **Atualização automática pelo instalador (*in-place upgrade*):** o instalador detecta instalações anteriores (`v1.0.0+`), encerra com segurança a instância instalada antes de substituir os arquivos e preserva todas as preferências e atalhos sem exigir desinstalação manual.
- **Diagnóstico HID e modo de simulação:** inspeção somente-leitura de interfaces/relatórios HID e modo de simulação isolado com valores fictícios ajustáveis.

## Instalação e atualização

Baixe um dos pacotes para **Windows x64** na página de [GitHub Releases](https://github.com/kybiy/Wireless-Device-Battery-Monitor/releases).

| Arquivo | Como usar |
| --- | --- |
| `WirelessBattery-1.1.0.exe` | **Recomendado.** Execute o instalador e siga o assistente. Inclui um runtime Java compatível embutido e instala no perfil do usuário (`%LOCALAPPDATA%\WirelessBattery\`) sem exigir permissões de Administrador. |
| `WirelessBattery-1.1.0-portable-win-x64.zip` | Extraia a pasta inteira e execute `WirelessBattery\WirelessBattery.exe`. O pacote portátil inclui o runtime Java e dispensa instalação. |

Conecte o dongle do periférico e ligue o dispositivo. Para o GameSir Cyclone 2, selecione o modo XInput.

### Atualizando uma versão já instalada (`v1.0.0` → `v1.1.0` ou futuras)

Não é necessário desinstalar manualmente a versão anterior nem fechar o aplicativo antes:

1. Baixe e execute `WirelessBattery-1.1.0.exe`.
2. O instalador detectará automaticamente a instalação existente pelo `UpgradeCode` fixo, encerrará com segurança apenas a instância instalada em execução (sem afetar cópias portáteis em outras pastas) e atualizará o executável, bibliotecas e runtime Java com `RemoveExistingProducts` agendado dentro da sequência transacional do Windows Installer (`After="InstallInitialize"`).
3. Suas configurações em `%USERPROFILE%\.wireless-battery-java\settings.properties`, seus atalhos (Área de Trabalho e Menu Iniciar) e sua preferência de inicialização automática com o Windows são integralmente preservados.

### Integridade e Windows SmartScreen

Na pasta do download, execute no PowerShell o comando correspondente ao arquivo escolhido e compare o resultado com a entrada do mesmo nome em `SHA256SUMS.txt` da Release.

```powershell
Get-FileHash .\WirelessBattery-1.1.0.exe -Algorithm SHA256
Get-FileHash .\WirelessBattery-1.1.0-portable-win-x64.zip -Algorithm SHA256
```

O Windows SmartScreen pode exibir um aviso para executáveis sem assinatura digital comercial. Confira a origem oficial do download e o hash SHA-256 antes de prosseguir (clique em **Mais informações** → **Executar assim mesmo**). Se o hash divergir, descarte o arquivo. Nunca desative o Windows Defender ou outras proteções do sistema.

## Créditos e projeto original

This is a Java recode of krisgfx9/Wireless-Device-Battery-Monitor (the original C++ BatteryHub by krisgfx9), adapted to support three specific wireless devices.

Este projeto é uma reimplementação em Java inspirada no Wireless-Device-Battery-Monitor, originalmente desenvolvido em C++ por krisgfx9. Todos os créditos pela criação do projeto original pertencem ao seu respectivo autor.

| Projeto de referência | Contribuição como referência | Licença do projeto consultado |
| --- | --- | --- |
| [krisgfx9/Wireless-Device-Battery-Monitor](https://github.com/krisgfx9/Wireless-Device-Battery-Monitor) | Projeto C++ original, conceito e referências de integração com a barra de tarefas e protocolos. | [GPL-3.0 ou posterior, conforme o README histórico](https://github.com/ferloom/Wireless-Device-Battery-Monitor/blob/f42a85f9b53ccb6d63aad0dd11a64787d71782ac/README.md#license) |
| [joaoarebalo/Wireless-Device-Battery-Monitor-Fallen](https://github.com/joaoarebalo/Wireless-Device-Battery-Monitor-Fallen) | Fork consultado durante o desenvolvimento (incluindo arquitetura `WS_CHILD` do widget nativo Win32). | [GPL-3.0 ou posterior](https://github.com/joaoarebalo/Wireless-Device-Battery-Monitor-Fallen#license) |
| [xb-bx/m800-pro-driver](https://github.com/xb-bx/m800-pro-driver) | Formato da consulta e posição dos campos de bateria do Delux M800 Pro PAW3395. | [GPL-2.0](https://github.com/xb-bx/m800-pro-driver/blob/master/LICENSE) |
| [vdemonchy/cyclone2-linux](https://github.com/vdemonchy/cyclone2-linux) | Referência para o protocolo do GameSir Cyclone 2. | [GPL-3.0](https://github.com/vdemonchy/cyclone2-linux/blob/HEAD/LICENSE) |
| [pedrosegato/gamesir-reverse-engineering](https://github.com/pedrosegato/gamesir-reverse-engineering) | Documentação do protocolo citada na implementação do GameSir. | [MIT](docs/licenses/gamesir-reverse-engineering-LICENSE.txt) |

A reimplementação Java é distribuída sob a [GNU General Public License v3.0](LICENSE). Os ícones SVG incluem desenhos de Lucide e Feather; consulte a [licença dos ícones](docs/licenses/lucide-LICENSE.txt) e os [avisos de redistribuição](docs/licenses/REDISTRIBUICAO.md).

## Informações técnicas

O projeto utiliza Java 17, Swing e FlatLaf (Apache-2.0), JSVG (MIT), hid4java (MIT) com HIDAPI e JNA (Apache-2.0 ou LGPL-2.1 ou posterior). JUnit 5 (EPL-2.0) é usado nos testes. Os JARs de FlatLaf, FlatLaf Extras, JSVG e JNA incluem suas licenças em `META-INF/LICENSE`. Consulte também os avisos de [hid4java](docs/licenses/hid4java-LICENSE.txt) e [HIDAPI](docs/licenses/hidapi-LICENSE.txt), incluindo a [opção BSD](docs/licenses/hidapi-LICENSE-bsd.txt).

As preferências ficam em `%USERPROFILE%\.wireless-battery-java\settings.properties`. A inicialização automática utiliza a chave `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` do usuário atual.

### Compilar, testar e executar

Com JDK 17 ou superior e Apache Maven, execute na raiz do projeto:

```powershell
mvn clean package
mvn exec:java
```

### Empacotar distribuição Windows (`app-image`, `.exe` atualizável e `.zip` portátil)

Após executar `mvn clean package`, utilize o script de empacotamento (requer JDK 25 com `jpackage` e **WiX Toolset 3.14+**):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1 `
  -Jdk "caminho\para\jdk-25" `
  -Wix "caminho\para\wix314"
```

O script compila o auxiliar de encerramento seguro (`packaging/wix/CloseHelper.cs`), aplica os templates WiX customizados (`packaging/wix/`), mantém o `--win-upgrade-uuid 7c2f4b5e-3d9a-4e8c-91f2-8a6b4d2e1c90` e gera `WirelessBattery-1.1.0.exe`, `WirelessBattery-1.1.0-portable-win-x64.zip` e `SHA256SUMS.txt` em `dist/`.
