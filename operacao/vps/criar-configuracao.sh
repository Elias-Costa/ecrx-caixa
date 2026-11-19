#!/bin/sh
# Gera o arquivo de configuração de uma instalação, uma vez: os segredos que nascem na máquina saem
# aleatórios, e o que vem de fora (domínio, mensalidade, buckets, credenciais da AWS, chave pública
# da cópia e endereços do vigia) fica vazio, para preencher à mão. Nunca sobrescreve um arquivo que
# já existe: trocar a senha do banco ou a chave dos tokens depois de no ar é outra operação.
#
# Uso: sh criar-configuracao.sh <pasta>, como root na VPS, com /etc/ecrx-caixa; no ensaio, uma
# pasta fora do repositório.
set -eu

PASTA=${1:?informe a pasta da configuração}
ARQUIVO="$PASTA/ecrx-caixa.env"

if [ -e "$ARQUIVO" ]; then
    echo "$ARQUIVO já existe e não foi alterado." >&2
    exit 1
fi

# Só o dono lê o que for criado daqui em diante.
umask 077
mkdir -p "$PASTA/efi" "$PASTA/inter"

# Hexadecimal, e não base64, para o valor não ter caractere que o arquivo de ambiente leia de outro
# jeito. 32 bytes aleatórios viram 64 caracteres, acima do mínimo de 32 da aplicação.
aleatorio() {
    openssl rand -hex 32
}

cat > "$ARQUIVO" <<FIM
# Configuração do ECRX Caixa nesta máquina, lida pelo compose.sh. Só o root lê este arquivo.
# Valores sem aspas e sem espaço em volta do sinal de igual.

# Domínio do aplicativo, com o registro A apontando para esta máquina.
ECRX_CAIXA_DOMINIO=

# Subdomínio só do aviso Pix da Efí, também com o registro A para esta máquina, onde o proxy exige o
# certificado de cliente dela. Vazio, o aviso não chega, e o Pix integrado fica sem confirmação por ele.
ECRX_CAIXA_WEBHOOK_DOMINIO=

# Subdomínio só do aviso Pix do Inter, com o registro A para esta máquina, onde o proxy exige o
# certificado de cliente do Inter. Vazio, o aviso das Contas do Inter não chega.
ECRX_CAIXA_WEBHOOK_DOMINIO_INTER=

# Gerados aqui. A senha do banco é a do papel ecrx_caixa, sem superusuário, com que a aplicação, a
# cópia, a restauração e o encerramento conectam; a do superusuário vai só para o contêiner do banco,
# e nenhum serviço conecta com ela. Trocar uma senha do banco depois exige trocá-la também dentro dele;
# trocar a chave dos tokens obriga todo mundo a entrar de novo; trocar o segredo dos planos invalida os
# códigos ainda não aplicados. Guarde o segredo dos planos também fora da máquina: o script do código
# de plano precisa dele.
ECRX_CAIXA_DB_PASSWORD=$(aleatorio)
ECRX_CAIXA_DB_SUPERUSUARIO_PASSWORD=$(aleatorio)
ECRX_CAIXA_JWT_SECRET=$(aleatorio)
ECRX_CAIXA_PLANO_SECRET=$(aleatorio)

# Mensalidade do plano completo em reais, com ponto decimal.
ECRX_CAIXA_MENSALIDADE_COMPLETO=

# Registro mínimo das remoções, num bucket separado das cópias, apagado pelo próprio bucket depois de
# 45 dias, mais que a vida da cópia mais antiga. A credencial só grava
# nele; o destino, no formato s3:<bucket>/remocoes, serve ao encerramento e à restauração.
ECRX_CAIXA_REMOCOES_BUCKET=
ECRX_CAIXA_REMOCOES_DESTINO=
ECRX_CAIXA_REMOCOES_AWS_ACCESS_KEY_ID=
ECRX_CAIXA_REMOCOES_AWS_SECRET_ACCESS_KEY=

# Remetente do resumo do caixa por e-mail, num domínio verificado no Amazon SES em São Paulo. A
# credencial de remoções acima também envia por ele. Vazio, o resumo fica desligado.
ECRX_CAIXA_RESUMO_REMETENTE=

# Cópia de hora em hora: a linha age1... da chave pública, os destinos s3:<bucket>/horaria e
# s3:<bucket>/diaria e a credencial que só grava objeto novo. A hora diária é em UTC.
ECRX_CAIXA_COPIA_CHAVE_PUBLICA=
ECRX_CAIXA_COPIA_DESTINO=
ECRX_CAIXA_COPIA_DESTINO_DIARIA=
ECRX_CAIXA_COPIA_HORA_DIARIA=6
ECRX_CAIXA_COPIA_AWS_ACCESS_KEY_ID=
ECRX_CAIXA_COPIA_AWS_SECRET_ACCESS_KEY=

# Endereços de sinal do vigia, um por check: o da cópia, o da máquina, o das recusas, o dos erros e o
# do deploy da madrugada.
ECRX_CAIXA_VIGIA_COPIA=
ECRX_CAIXA_VIGIA_MAQUINA=
ECRX_CAIXA_VIGIA_RECUSAS=
ECRX_CAIXA_VIGIA_ERROS=
ECRX_CAIXA_VIGIA_DEPLOY=
FIM

cat > "$PASTA/efi.env" <<FIM
# Credenciais Pix por Conta que recebe Pix, lidas só pela aplicação. Para cada uma, com o UUID da
# Conta em maiúsculas e sem hífens: ECRX_CAIXA_EFI_<UUID>_AMBIENTE, _CLIENT_ID, _CLIENT_SECRET,
# _CHAVE_PIX, _CERTIFICADO_P12 (/segredos/efi/<uuid da Conta>.p12, com o arquivo na pasta efi ao
# lado), _CERTIFICADO_SENHA, _WEBHOOK_ID e _WEBHOOK_SECRET. Os dois do webhook vão na URL que a Efí
# chama: só letras, números, hífen e sublinhado, como os de openssl rand -hex 32, e um par por Conta.
FIM

cat > "$PASTA/inter.env" <<FIM
# Credenciais Pix do Inter por Conta, lidas só pela aplicação. Para cada uma, com o UUID da Conta em
# maiúsculas e sem hífens: ECRX_CAIXA_INTER_<UUID>_AMBIENTE (HOMOLOGACAO ou PRODUCAO), _CLIENT_ID,
# _CLIENT_SECRET, _CHAVE_PIX, _CERTIFICADO_CRT e _CERTIFICADO_KEY (/segredos/inter/<uuid da Conta>.crt
# e .key, com os arquivos na pasta inter ao lado), _CONTA_CORRENTE (só com mais de uma conta na
# integração), _WEBHOOK_ID e _WEBHOOK_SECRET. Uma Conta tem a Efí ou o Inter, nunca os dois.
FIM

echo "Gerado $ARQUIVO. Preencha os valores vazios antes do primeiro deploy."
