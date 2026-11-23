# Wireless Battery — Monitor de Dispositivos (Java 17 + Maven + NetBeans + FlatLaf)

Monitor de bateria minimalista, elegante e compacto para periféricos sem fio no Windows 10/11, desenvolvido em **Java 17**, **Maven**, **Swing + FlatLaf + FlatLaf Extras (SVG)**, **JNA** e **hid4java**.

Inspirado no visual minimalista e nos indicadores de barra de tarefas/bandeja do projeto de referência GPL-3.0 (`Wireless-Device-Battery-Monitor`), com interface no estilo dos utilitários nativos do Windows 11.

---

## Dispositivos suportados

1. **Delux M800 Pro PAW3395** (Mouse — Receptor USB 2.4 GHz / Cabo):
   - Consulta somente-leitura via HID Feature Report `0x0C` baseada em [`xb-bx/m800-pro-driver`](https://github.com/xb-bx/m800-pro-driver).
   - VID/PID/interface configuráveis (`0x248A:0x5B2F` sem fio, `0x248A:0x5B2E` cabo, `IF 1`).
   - **Estado:** *Experimental — aguardando validação em hardware real no Windows.*
2. **Akko Verge S9** (Headset — Receptor USB 2.4 GHz):
   - Estrutura preparada com verificação passiva de presença e identificação de coleções HID padrão (`0x84`, `0x85`, `0x06:0x20`) ou proprietárias (`0xFF00+`).
   - **Segurança:** nunca envia comandos desconhecidos ao headset.
   - **Estado:** *Pendente de identificação do protocolo do receptor 2.4 GHz via Diagnóstico HID.*
3. **GameSir Cyclone 2** (Controle — Receptor USB 2.4 GHz / Cabo em modo XInput):
   - Consulta somente-leitura na coleção proprietária `0xFFF0:0x0040` (`VID 0x3537`, `PID 0x100B` ativo / `0x0575` receptor ocioso), baseada em `src/drivers/gamesir.cpp` (`PollGameSirCyclone` / protocolo `cyclone2-linux`).
   - Envia apenas Output Report `0x0F 0x03` para receber o Input Report `0x12` (`byte[35]` carregamento, `byte[36]` percentual `0–100`).
   - **Estado:** *Experimental — aguardando validação em hardware real no Windows.*

---

## Sistema de design e ícones

- **Janela compacta (`560×450`)** com memória da última posição na tela (com validação multi-monitor).
- **Tema Escuro (padrão):** fundo `#101114`, cartões `#1A1C20`, superfície elevada `#22252B`, texto `#F1F3F5` / `#9CA3AF`, bordas `#24272E`/`#282C34`, destaque `#7C9EFF`.
- **Tema Claro equivalente** selecionável nas configurações ou no menu da bandeja.
- **Cores semânticas de bateria:** alta `#59B88A` (> 50%), média `#D5AA59` (21%–50%), baixa `#E16B70` (≤ 20%) e carregando `#77A7ED`.
- **Ícones vetoriais SVG estilo Lucide (`2.15 px`)** empacotados localmente em `src/main/resources/icons/` (`mouse.svg`, `headset.svg`, `gamepad.svg`, `battery.svg`, `zap.svg`, etc.) e renderizados offline via `FlatSVGIcon` com suporte a HiDPI.

---

## Integração com a barra de tarefas e bandeja do Windows

- **Modo 1 — Bandeja do sistema (`SystemTray`):**
  - Ícones individuais por dispositivo com dígitos nítidos, barra inferior de nível, esmaecimento quando em repouso e cache de assinatura visual/menu para evitar piscar.
  - Menu de contexto completo: exibir/ocultar dispositivos individualmente, alternar modo de exibição, atualizar agora, abrir aplicativo, configurações, iniciar com o Windows e sair.
- **Modo 2 — Widget na barra de tarefas (`TaskbarWidgetWindow` + `WindowsTaskbarLocator`):**
  - Exibe os dispositivos horizontalmente próximo ao relógio (`[Mouse] 85%   [Headset] 62%   [Controle] 90%`).
  - Lê via JNA (somente leitura) `Shell_TrayWnd`, `TrayNotifyWnd`, `ShellFeedsTaskbarViewWindow`, `SHAppBarMessage(ABM_GETSTATE)`, `SHQueryUserNotificationState` (oculta automaticamente em tela cheia/jogos) e tema da barra de tarefas (`SystemUsesLightTheme`).
  - Permite configurar tamanho dos ícones (`12–24 px`), fonte (`10–18 pt`), espaçamento (`4–32 px`), deslocamento horizontal (`0–300 px`) e fundo discreto.
  - **Fallback automático** para o `SystemTray` se a barra de tarefas for vertical, oculta ou indisponível.

> **Limitação técnica em Java:** diferentemente do executável C++ nativo que anexa uma `HWND` filha dentro de `Shell_TrayWnd`, em Java/Swing utiliza-se uma `JWindow` utilitária sem foco alinhada via JNA para manter a estabilidade do Windows Explorer sem modificar nem injetar código no processo do sistema.

---

## Como compilar e executar

```powershell
mvn clean package
```

No **Apache NetBeans**: **Arquivo → Abrir Projeto** → selecione `WirelessBatteryJava-NetBeans` → **Executar** (`br.com.wirelessbattery.Main`).

---

## Configuração opcional de VID/PID (`devices.properties`)

```properties
# Delux M800 Pro PAW3395
wirelessbattery.m800pro.vid=0x248A
wirelessbattery.m800pro.pid.wireless=0x5B2F
wirelessbattery.m800pro.pid.wired=0x5B2E
wirelessbattery.m800pro.interface=1

# GameSir Cyclone 2
wirelessbattery.cyclone2.vid=0x3537
wirelessbattery.cyclone2.pid.active=0x100B
wirelessbattery.cyclone2.pid.idle=0x0575

# Akko Verge S9 (somente detecção passiva após coleta no Diagnóstico HID)
# wirelessbattery.akkoverges9.vid=0x....
# wirelessbattery.akkoverges9.pid=0x....
```

---

## Licença

Distribuído sob **GNU General Public License v3.0 (GPL-3.0)** — veja [LICENSE](LICENSE).
