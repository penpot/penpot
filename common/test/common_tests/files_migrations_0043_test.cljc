;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0043-test
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; 0043-repair-svg-attr-maps
;; Some imported files hold an svg attribute other than `:style` as a map an
;; old reader split out of its text. A reference to one of the shape's defs
;; becomes that reference again; any other map value drops.

(def ^:private migration-id
  "0043-repair-svg-attr-maps")

(defn- migrate-attrs
  "The `:svg-attrs` the migration leaves on a page shape with `attrs` and
   `defs`, and on a deleted component's shape alike."
  [attrs defs]
  (let [shape-id     (uuid/next)
        page-id      (uuid/next)
        component-id (uuid/next)
        shape        {:id shape-id :type :rect :svg-attrs attrs :svg-defs defs}
        data         {:pages-index {page-id {:objects {shape-id shape}}}
                      :components  {component-id {:objects {shape-id shape}}}}
        data'        (cfm/migrate-data data migration-id)]
    [(get-in data' [:pages-index page-id :objects shape-id :svg-attrs])
     (get-in data' [:components component-id :objects shape-id :svg-attrs])]))

(t/deftest migration-0043-restores-a-split-defs-reference
  (let [defs {"filter0_d_16837_160" {:tag :filter :attrs {:id "filter0_d_16837_160"}}}]
    (doseq [value [{(keyword "url(#filter0_d_16837_160)") nil}
                   ;; as 0037 left it, kebab-cased
                   {(keyword "url(-filter0-d-16837-160)") nil}]
            attrs (migrate-attrs {:filter value :fill "none"} defs)]
      (t/is (= {:filter "url(#filter0_d_16837_160)" :fill "none"} attrs)))))

(t/deftest migration-0043-drops-other-map-values-and-keeps-style
  (let [style {:fill "red"}]
    (doseq [attrs (migrate-attrs {:filter {(keyword "url(#missing)") nil}
                                  :mask {:a 1}
                                  :style style}
                                 {})]
      (t/is (= {:style style} attrs)))))

(t/deftest migration-0043-leaves-scalar-attrs-alone
  (let [attrs {:filter "url(#f)" :fill "none"}]
    (doseq [attrs' (migrate-attrs attrs {"f" {:tag :filter :attrs {:id "f"}}})]
      (t/is (= attrs attrs')))))
