Current version: 1.28.0

Build browserified bundle:

    ./node_modules/browserify/bin/cmd.js -s dateFns -e src/index.js -o datefns.js

Minified bundle:

    ./node_modules/uglify-js/bin/uglifyjs datefns.js -m -o datefns.min.js
