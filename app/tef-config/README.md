# Configuração CliSiTef (GPOS)

Coloque aqui os arquivos homologados do terminal:

| Arquivo local | Vai para o APK / app |
|---------------|----------------------|
| `clsit.ini` | `src/main/assets/CLSIT` (sem extensão, maiúsculas) |
| `cheques.ini` | `src/main/assets/cheques.ini` e `Cheque.ini` |

Após alterar, rode o build ou execute:

```bat
gradlew.bat prepareTefAssets assembleDebug
```

Referência Modulo Info: `G:\programas\MOBILE\GPOS\SITEF\libs\clisitef\assets\`
