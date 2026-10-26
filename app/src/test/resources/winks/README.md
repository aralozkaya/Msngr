# Wink fixtures

`screen-punch.mco` is the Escargot catalog installer from:
https://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/winks/files/screen-punch-2.mco

`screen-punch.cab` is the unmodified `TFR35A.cab` nested in that installer. It contains `content.xml`, `punch.png`, and `punch.swf`. The original stamp is retained. These are package fixtures; they are not a captured live exchange.

P2P request AppID 1 / MSNObject EUF GUID and chunked datacast formatting are corroborated by the aMSN implementation:
https://github.com/tomhennigan/amsn/blob/master/amsn/msnp2p.tcl
https://github.com/tomhennigan/amsn/blob/master/amsn/plugins/winks/winks.tcl

`screen-punch-lzx.cab` repackages the same three inner files using Windows makecab LZX compression (21-bit window), to verify the alternate CAB decoder. It is a parser fixture and must not be sent with the original CAB signature.
