# StorageFiller の R8 用ルール（v0.8.2〜）。
# 画面部品（DonutChartView）・ApkProvider・Activity はマニフェストとレイアウトから自動で残る。
# Google Code Scanner / ML Kit / Play 開発者サービスは各ライブラリ同梱のルールで残る。
# このアプリのコードは名前で部品を呼び出す仕組み（リフレクション）を使っていない。
# 実機確認で落ちる箇所が見つかったら、ここに -keep を足して理由を書く。
