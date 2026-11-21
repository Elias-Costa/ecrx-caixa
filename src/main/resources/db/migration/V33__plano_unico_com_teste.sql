-- Um plano só, o completo, e toda Conta começa num teste de quatorze dias dele (RF31). Sai o plano
-- gratuito: depois do vencimento sem pagamento, do teste ou de um mês pago, vêm três dias úteis de
-- tolerância e, depois deles, a Conta fica só leitura até pagar.
--
-- O teste vence como um período pago: o fim dele é o primeiro vencimento, e o dia dele é o dia do
-- vencimento de todos os meses. Por isso o ciclo deixa de ser nulo em qualquer Conta.
ALTER TABLE conta ADD COLUMN em_teste boolean NOT NULL DEFAULT false;

-- A Conta do gratuito passa ao teste de quatorze dias contados do dia desta migration, no fuso do
-- balcão, como se nascesse hoje. A do completo fica com o vencimento que tinha e passa à tolerância
-- nova.
UPDATE conta
   SET plano              = 'COMPLETO',
       em_teste           = true,
       proximo_vencimento = (now() AT TIME ZONE 'America/Bahia')::date + 14,
       dia_de_vencimento  = extract(day FROM (now() AT TIME ZONE 'America/Bahia')::date + 14)::integer
 WHERE plano = 'GRATIS';

-- O pedido de adesão aberto foi feito no gratuito, para sair dele, e o código dele não vale mais: a
-- adesão passa a ser o pagamento de quem está só leitura, com outro período.
UPDATE pedido_de_plano
   SET situacao = 'SUBSTITUIDO'
 WHERE situacao = 'ABERTO'
   AND tipo = 'ADESAO';

ALTER TABLE conta ALTER COLUMN em_teste DROP DEFAULT;

ALTER TABLE conta
    DROP CONSTRAINT conta_ciclo_so_no_plano_pago,
    DROP CONSTRAINT conta_plano_valido,
    ADD CONSTRAINT conta_plano_valido CHECK (plano = 'COMPLETO'),
    ALTER COLUMN dia_de_vencimento SET NOT NULL,
    ALTER COLUMN proximo_vencimento SET NOT NULL;

COMMENT ON COLUMN conta.em_teste IS
    'Verdadeiro enquanto nenhum período foi pago: o próximo vencimento é o fim do teste.';
COMMENT ON COLUMN conta.dia_de_vencimento IS
    'Dia do mês em que o plano vence: o do fim do teste, ou o do pagamento feito no só leitura, quando o ciclo recomeça.';
COMMENT ON COLUMN conta.proximo_vencimento IS
    'Fim, exclusive, do teste ou do último período pago. A tolerância e o só leitura contam a partir dele.';

COMMENT ON TABLE pedido_de_plano IS
    'Pedido de renovação, ou de adesão da Conta em só leitura, ativado por um código conferido pela aplicação.';
COMMENT ON COLUMN pedido_de_plano.tipo IS
    'RENOVACAO paga o período que começa no vencimento; ADESAO, feita no só leitura, recomeça o ciclo no dia do código. As adesões de antes saíam do plano gratuito.';
COMMENT ON COLUMN pedido_de_plano.periodo_inicio IS
    'Primeiro dia do período pago. Na adesão, nulo até a aplicação do código, quando o ciclo recomeça.';
