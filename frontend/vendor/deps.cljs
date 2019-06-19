{:foreign-libs
 [{:file "snapsvg/snap.svg.js"
   :file-min "snapsvg/snap.svg.min.js"
   :provides ["vendor.snapsvg"]}
  {:file "date-fns/src/index.js"
   :module-type :es6
   :provides ["vendor2.dateFns"]}
  {:file "jszip/jszip.js"
   :file-min "jszip/jszip.min.js"
   :provides ["vendor.jszip"]}
  {:file "datefns/datefns.js"
   :file-min "datefns/datefns.min.js"
   :provides ["vendor.datefns"]}]
 :externs ["main.externs.js"
           "snapsvg/externs.js"
           "jszip/externs.js"
           "datefns/externs.js"]}
