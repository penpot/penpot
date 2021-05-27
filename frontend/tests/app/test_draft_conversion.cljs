(ns app.test-draft-conversion
  (:require
   [app.common.data :as d]
   [app.common.text :as txt]
   [cljs.test :as t :include-macros true]
   [cljs.pprint :refer [pprint]]))

(t/deftest test-basic-conversion-roundtrip
  (let [text    "qwqw 🠒"
        content {:type "root",
                 :children
                 [{:type "paragraph-set",
                   :children
                   [{:key "cfjh",
                     :type "paragraph",
                     :children
                     [{:font-id "gfont-roboto",
                       :font-family "Roboto",
                       :font-variant-id "regular",
                       :font-weight "400",
                       :font-style "normal",
                       :text text}]}]}]}]
    (cljs.pprint/pprint (txt/convert-to-draft content))
    (cljs.pprint/pprint (txt/convert-from-draft (txt/convert-to-draft content)))
    (t/is (= (txt/convert-from-draft (txt/convert-to-draft content))
             content))))

