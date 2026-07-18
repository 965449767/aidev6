# Syntax check all scripts with dash (aligns with real PRoot /bin/sh=dash)
for f in "$ASSETS_DIR"/*.sh; do
    assert_syntax_ok "$f"
    assert_dash_compatible "$f"
done
