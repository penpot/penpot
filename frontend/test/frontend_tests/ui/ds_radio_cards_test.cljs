;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.ds-radio-cards-test
  "The DS radio cards are one native radio per option, inside a group: the
  selected one is checked, a pick reports its value, a disabled one
  cannot be picked, and each option is named by its label and its
  description. The document comes from jsdom, since the runner has none."
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/client" :as rdc]
   [app.main.ui.ds.controls.radio-cards :refer [radio-cards*]]
   [app.util.globals :as globals]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [rumext.v2 :as mf]))

(def ^:private options
  [{:id "mode-merge"
    :value "merge"
    :icon "git-merge"
    :label "Merge and delete the branch"
    :description "The branch is deleted"}
   {:id "mode-apply"
    :value "apply"
    :icon "git-branch"
    :label "Apply and keep the branch"
    :description "Update the branch first"
    :disabled true}
   {:id "mode-other"
    :value "other"
    :label "Something else"}])

(mf/defc host*
  {::mf/private true}
  [{:keys [on-change]}]
  [:> radio-cards* {:name "mode"
                    :aria-label "What happens to the branch"
                    :selected "merge"
                    :options options
                    :on-change on-change}])

(defn- ^:async with-host
  "Mounts `host*` in a jsdom document and calls `test-fn` with the
  document and the values the cards reported (an atom)."
  [test-fn]
  (let [dom          (JSDOM. "<!doctype html><html><body><div id='root'></div></body></html>")
        window       (.-window dom)
        document     (.-document window)
        old-window   (.-window js/globalThis)
        old-document (.-document js/globalThis)
        old-act      (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        picks        (atom [])
        on-change    (fn [value _] (swap! picks conj value))]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (try
      (await
       (mock/with-mocks* {globals/document document globals/window window}
         (let [root (rdc/createRoot (.getElementById document "root"))]
           (react/act #(.render root (mf/element host* #js {:onChange on-change})))
           (await (test-fn document picks))
           (await (react/act #(.unmount root))))))
      (finally
        (set! (.-window js/globalThis) old-window)
        (set! (.-document js/globalThis) old-document)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) old-act)))))

(defn- radio
  [document id]
  (.getElementById document id))

(t/deftest ^:async the-cards-are-a-group-of-native-radios
  (await
   (with-host
     (fn [document _]
       (let [group (.querySelector document "[role=radiogroup]")]
         (t/is (some? group))
         (t/is (= "What happens to the branch" (.getAttribute group "aria-label")))
         (t/is (= 3 (.-length (.querySelectorAll group "input[type=radio][name=mode]")))))
       (t/is (true? (.-checked (radio document "mode-merge"))) "the selected one is checked")
       (t/is (false? (.-checked (radio document "mode-other"))))))))

(t/deftest ^:async a-pick-reports-its-value
  (await
   (with-host
     (fn [document picks]
       (react/act #(.click (radio document "mode-other")))
       (t/is (= ["other"] @picks))))))

(t/deftest ^:async a-disabled-card-cannot-be-picked
  (await
   (with-host
     (fn [document picks]
       (t/is (true? (.-disabled (radio document "mode-apply"))))
       (react/act #(.click (radio document "mode-apply")))
       (t/is (= [] @picks))))))

(t/deftest ^:async each-card-is-named-by-its-label-and-its-description
  (await
   (with-host
     (fn [document _]
       (let [label (.-parentElement (radio document "mode-merge"))]
         (t/is (= "LABEL" (.-tagName label)) "the radio sits in its label")
         (t/is (= "Merge and delete the branchThe branch is deleted"
                  (.-textContent label))))
       (t/is (= "Something else"
                (.-textContent (.-parentElement (radio document "mode-other"))))
             "an option without a description is named by its label alone")))))
