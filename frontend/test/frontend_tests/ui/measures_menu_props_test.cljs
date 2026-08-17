;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.measures-menu-props-test
  (:require
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [check-measures-menu-props]]
   [cljs.test :as t :include-macros true]))

;; Shared, identical-by-reference props so the comparator only reacts to the
;; `values` differences we are testing.
(def ^:private ids #js ["id-1"])
(def ^:private shape-type :rect)
(def ^:private tokens #js {})

(defn- props
  [values]
  #js {"ids" ids "type" shape-type "appliedTokens" tokens "values" values})

(def ^:private base-values
  {:width 100
   :height 200
   :layout-item-h-sizing :fix
   :layout-item-v-sizing :fix})

(t/deftest test-check-measures-menu-props
  (t/testing "skips re-render when nothing relevant changed"
    ;; Different map instances with identical scalar content must be treated
    ;; as equal (returns true => memoized, no re-render).
    (t/is (true? (check-measures-menu-props
                  (props base-values)
                  (props (into {} base-values))))))

  (t/testing "re-renders when horizontal sizing changes but width does not"
    ;; Regression test: toggling fix <-> auto without changing the width value
    ;; must force a re-render so the width input enabled/disabled state updates.
    (t/is (false? (check-measures-menu-props
                   (props base-values)
                   (props (assoc base-values :layout-item-h-sizing :auto))))))

  (t/testing "re-renders when vertical sizing changes but height does not"
    (t/is (false? (check-measures-menu-props
                   (props base-values)
                   (props (assoc base-values :layout-item-v-sizing :auto))))))

  (t/testing "re-renders when width changes"
    (t/is (false? (check-measures-menu-props
                   (props base-values)
                   (props (assoc base-values :width 150)))))))
