;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.branches-compare-labels-test
  "The compare dialog names properties, booleans, numbers and collection
  counts through the catalogue, so a translation replaces every one of
  them. The catalogue here is a stand-in for en.po: its values are picked
  to differ from the English the dialog used to hardcode."
  (:require
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]))

;; The formatters under test.
(def ^:private attr-label brc/attr-label)
(def ^:private display-val brc/display-val)
(def ^:private get-translations @#'i18n/get-translations)

(def ^:private catalogue
  #js {"workspace.branches.compare.attr.corner-radius" "Radio de esquina"
       "workspace.branches.compare.attr.component-reference" "Referencia de componente"
       "workspace.branches.compare.value-true" "verdadero"
       "workspace.branches.compare.value-false" "falso"
       "workspace.branches.compare.n-items" #js ["1 elemento" "%s elementos"]
       "workspace.branches.compare.n-props" #js ["1 propiedad" "%s propiedades"]})

(defn- with-catalogue
  "Install the stand-in catalogue for one test and put the previous one
  back, so other test namespaces keep their own strings."
  [f]
  (let [translations (get-translations)
        previous     (unchecked-get translations "en")]
    (i18n/set-default-translations catalogue)
    (try
      (f)
      (finally
        (i18n/set-translations "en" previous)))))

(t/use-fixtures :each with-catalogue)

(t/deftest property-labels-come-from-the-catalogue
  (t/is (= "Radio de esquina" (attr-label :rx)))
  (t/is (= "Radio de esquina" (attr-label :ry)))
  (t/is (= "Referencia de componente" (attr-label :shape-ref))))

(t/deftest an-attr-with-no-name-of-its-own-falls-back-to-its-key
  (t/is (= "Some internal attr" (attr-label :some-internal-attr))
        "an attr the catalogue does not name stays a humanised key"))

(t/deftest boolean-values-come-from-the-catalogue
  (t/is (= "verdadero" (display-val true)))
  (t/is (= "falso" (display-val false))))

(t/deftest collection-counts-choose-the-plural-form
  (t/is (= "1 elemento" (display-val [1]))
        "a single item reads in the singular")
  (t/is (= "3 elementos" (display-val [1 2 3])))
  (t/is (= "1 propiedad" (display-val {:x 1})))
  (t/is (= "2 propiedades" (display-val {:x 1 :y 2}))))

(t/deftest numbers-are-punctuated-by-the-locale
  (binding [i18n/*current-locale* "es"]
    (t/is (= "439,5" (display-val 439.5))
          "a Spanish locale reads the decimal comma"))
  (binding [i18n/*current-locale* "en"]
    (t/is (= "439.5" (display-val 439.5)))
    (t/is (= "90" (display-val 89.99999)) "at most two decimals")
    (t/is (= "386" (display-val 386)))))
