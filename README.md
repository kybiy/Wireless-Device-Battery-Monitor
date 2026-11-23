# Wireless Battery Java — projeto Maven para NetBeans

Port inicial **parcial** do projeto original `joaoarebalo/Wireless-Device-Battery-Monitor-Fallen`.

## Como abrir
1. Instale JDK 17 ou superior, Apache NetBeans e Maven (Maven já costuma estar integrado ao NetBeans).
2. NetBeans → Arquivo → Abrir Projeto → selecione esta pasta (`WirelessBatteryJava-NetBeans`).
3. Aguarde o Maven obter `org.hid4java:hid4java:0.8.0` do Maven Central.
4. Clique com botão direito no projeto → Executar (classe principal `br.com.wirelessbattery.Main`).
5. Conecte o receptor USB 2.4 GHz do Fallen Pantera Pro Wireless e teste no Windows.

**Não execute como administrador inicialmente.** O aplicativo não baixa programas auxiliares e não se cadastra na inicialização do Windows.

## Implementado
- Janela Swing editável em NetBeans, log e atualização manual/automática (20 s)
- Ícone na área de notificação do Windows (não incorporado à barra de tarefas como no C++)
- Driver Areson para `25A7:FA07`, `FA08`, `FA7B`, `FA7C`, incluindo Fallen Pantera Pro
- Pacote `GetPower` e validação checksum 0x55; porcentagem e status de carregamento
- Comunicação HID por biblioteca hid4java

## Limitações importantes
- **Não é conversão completa** dos ~40 drivers C++ do projeto original: Bluetooth genérico, Xbox, Logitech, AULA, headsetcontrol etc. ainda não foram portados.
- Não foi compilado com Maven nem testado em hardware Windows neste ambiente. A biblioteca Maven precisa ser baixada pela sua IDE e o protocolo precisa de teste real.
- O C++ usa dois handles HID separados e abre a coleção de comando com acesso mínimo; o hid4java pode falhar ao abrir uma coleção que o Windows reserve. Se isso ocorrer, será necessário um pequeno bridge nativo Win32/JNA para reproduzir a semântica exata.
- Se o mouse dormir, o programa pode indicar que não conseguiu ler, em vez de preservar a última leitura como o original.
- Não há instalador; execute no NetBeans ou empacote posteriormente.

## Segurança e licença
Projeto derivado do original sob GPL-3.0. Preserve a licença e atribuição ao redistribuir. O aplicativo consulta dispositivos HID locais; não contém telemetria e não instala utilitários externos. O Maven acessará o Maven Central para baixar dependências quando executado pela primeira vez.

Código original: https://github.com/joaoarebalo/Wireless-Device-Battery-Monitor-Fallen
Biblioteca HID: https://github.com/gary-rowe/hid4java
