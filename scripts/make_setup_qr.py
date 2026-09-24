#!/usr/bin/env python3
"""チャレンジ記録アプリの初回設定QRコードを作る。

`https://storagefiller.invalid/setup?url=<url>&password=<password>` を1枚のQR PNGにして
一時ディレクトリへ書き出す。端末の標準カメラでこれを読み取り、デコードされた文字列を
「コピー」してから、アプリの「チャレンジ記録」画面にある「クリップボードから読み込む」
ボタンを押すと、URL・パスワード欄が埋まる（端末IDは端末ごとに違うためQRには含めず、
アプリ側で手入力する）。

以前はこのURLをカメラに直接「開かせる」方式を試したが、Android 12以降は
autoVerify無しのhttps intent-filterをアプリ側で明示的に有効化しないとチューザーすら
出さず、確認した実機（Android 16）ではブラウザへ無言で転送されるだけだった
（＝クリップボード経由が唯一実用的な受け渡し方法）。https://storagefiller.invalid/...
の形をそのまま使い続けているのは、カメラの多くが `storagefiller://` のようなカスタム
スキームよりもhttps形式のテキストの方をきれいにデコード・保持しやすいため。
`storagefiller.invalid` はRFC 2606で予約された、絶対に名前解決されないTLDなので、
誤ってブラウザで開かれても実在のどこかへ送られることはない（アプリ側はこのURLも
`storagefiller://setup` も同様に受け付ける）。

QR画像そのものがパスワードなので、このスクリプトを実行する本人以外の
目に触れないよう扱うこと。

- パスワードはコマンドライン引数では受け取らない（シェル履歴に残るため）。
  getpass で対話的に受け取る
- 書き出し先はカレントディレクトリではなく一時ディレクトリにする
- 読み取り後はPNGファイルを削除すること（実行後に案内する）

使い方:
    python scripts/make_setup_qr.py
"""

import getpass
import sys
import tempfile
from pathlib import Path
from urllib.parse import quote

import segno


def main() -> int:
    url = input("記録APIのURL (https://... ): ").strip()
    if not url:
        print("URLが空です。中止します。", file=sys.stderr)
        return 1
    if not url.startswith("https://"):
        print("URLは https:// で始めてください。中止します。", file=sys.stderr)
        return 1

    password = getpass.getpass("共通パスワード（入力は表示されません）: ")
    if not password:
        print("パスワードが空です。中止します。", file=sys.stderr)
        return 1

    # 値ごとにパーセントエンコードしてからURIへ組む（'/' や ':' も含めて全て変換する）
    uri = "https://storagefiller.invalid/setup?url={}&password={}".format(
        quote(url, safe=""), quote(password, safe="")
    )

    out_dir = Path(tempfile.mkdtemp(prefix="storagefiller-setup-qr-"))
    out_path = out_dir / "setup-qr.png"
    segno.make(uri, error="h").save(str(out_path), scale=10, border=4)

    print(f"QRコードを書き出しました: {out_path}")
    print("端末のカメラで読み取り、デコードされた文字列を「コピー」してください。")
    print("その後アプリの「チャレンジ記録」画面で「クリップボードから読み込む」を")
    print("押すとURL・パスワード欄が埋まります（リンクとして開く必要はありません）。")
    print("読み取ったらこのファイルを必ず削除してください。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
