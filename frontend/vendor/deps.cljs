{:foreign-libs
 [{:file "snapsvg/snap.svg.js"
   :file-min "snapsvg/snap.svg.min.js"
   :provides ["vendor.snapsvg"]}
  {:file "jszip/jszip.js"
   :file-min "jszip/jszip.min.js"
   :provides ["vendor.jszip"]}
  {:file "datefns/datefns.bundle.js"
   :file-min "datefns/datefns.bundle.min.js"
   :provides ["vendor.datefns"]}
  {:file "react-color/react-color.bundle.js"
   :file-min "react-color/react-color.bundle.min.js"
   :requires ["cljsjs.react"]
   :provides ["vendor.react-color"]}]
 :externs ["main.externs.js"
           "snapsvg/externs.js"
           "jszip/externs.js"
           "react-color/externs.js"
           "datefns/externs.js"]}
