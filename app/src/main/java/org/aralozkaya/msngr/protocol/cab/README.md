# CAB LZX decoder

The three LZX Java files are derived from Dorkbox CabParser at commit `e81fc32929ab7ce3bb995f8c7279101365137d29`:
https://github.com/dorkbox/CabParser

Copyright 2012 Dorkbox LLC. Apache License 2.0; see `assets/licenses/CabParser-Apache-2.0.txt`.
Changes: package relocated to `org.aralozkaya.msngr.protocol.cab`, imports redirected to the local exception/interface shims. The surrounding archive parser, bounds, storage and MSZIP decoder are Msngr code. No upstream update checker or filesystem extractor is included.
