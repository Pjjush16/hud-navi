#!/usr/bin/env python3
"""
Rebuild APK with native .so files stored as STORED (uncompressed).
This is needed because Gradle always compresses native libs from AARs,
even when useLegacyPackaging=true is set.
"""
import sys
import zipfile
import os

def fix_apk(src_apk, dst_apk):
    with zipfile.ZipFile(src_apk, 'r') as zin:
        with zipfile.ZipFile(dst_apk, 'w', zipfile.ZIP_STORED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                
                if item.filename.startswith('lib/') and item.filename.endswith('.so'):
                    # Native lib: force STORED (uncompressed)
                    new_item = zipfile.ZipInfo(item.filename)
                    new_item.compress_type = zipfile.ZIP_STORED
                    new_item.external_attr = item.external_attr
                    new_item.create_system = 0
                    zout.writestr(new_item, data)
                else:
                    # Keep original compression
                    new_item = zipfile.ZipInfo(item.filename)
                    new_item.compress_type = item.compress_type
                    new_item.external_attr = item.external_attr
                    new_item.create_system = item.create_system
                    zout.writestr(new_item, data)
    
    print(f"  Rebuilt: {os.path.basename(src_apk)} -> STORED native libs")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input.apk> <output.apk>")
        sys.exit(1)
    fix_apk(sys.argv[1], sys.argv[2])
