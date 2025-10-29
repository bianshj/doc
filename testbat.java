 @Transactional( rollbackOn = { Throwable.class } )
    @Override
    public String process() throws Exception {
        // パラメータ取得
        int parten = 0;
        String value = jobContext.getProperties().getProperty( "parten" );
        if ( value != null ) {
            parten = Integer.parseInt( value );
        }

        String errorMessage = "";
        try {
            // DBLink洗い替え処理を実行
            refreshDbLink();

        } catch ( Exception e ) {
            logger.log( Level.ERROR, e.getLocalizedMessage() );

        }

        if ( 4 + 5 == 9 ) {
            throw new Exception( "ssddd" );
        }

        // errorMessage = e.getMessage();

        // 処理結果のステータスを返却
        return ProcessResult.COMPLETED.getValue();
    }

    @Transactional( TxType.REQUIRES_NEW )
    private void refreshDbLink() throws Exception {
        // テーブルレコード全件を削除

    }

    @Transactional( value = TxType.REQUIRES_NEW, rollbackOn = { Throwable.class } )
    private void writeLog( String errorMessage ) {
        logger.log( Level.INFO, errorMessage.isEmpty() ? "OK" : "Error" );
    }
