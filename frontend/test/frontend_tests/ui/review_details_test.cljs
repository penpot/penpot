;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.review-details-test
  (:require
   ["react-dom/server" :as rds]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.review :as dwrv]
   [app.main.repo :as rp]
   [app.main.ui.workspace.review.common :as rvc]
   [app.main.ui.workspace.review.details :as details]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private display-attr @#'details/display-attr)

(def ^:private activity-fetched @#'dwrv/activity-fetched)

(def ^:private card-layer @#'details/card-layer)

(def ^:private card-preview* @#'details/card-preview*)

(t/deftest a-fill-without-tokens-shows-its-value
  (let [fill-a [{:fill-color "#ff0000" :fill-opacity 1}]
        fill-b [{:fill-color "#00ff00" :fill-opacity 1}]
        [_ before after] (display-attr :fills [fill-a fill-b] nil)]
    (t/is (= fill-a before))
    (t/is (= fill-b after))))

(t/deftest a-token-on-one-side-shows-the-token
  (let [fill [{:fill-color "#ff0000" :fill-opacity 1}]
        [_ before after] (display-attr :fills [fill fill] [nil {:fill "brand.primary"}])]
    (t/is (= fill before))
    (t/is (= "brand.primary" after))))

(t/deftest a-property-that-takes-no-tokens-shows-its-value
  (let [[_ before after] (display-attr :opacity [0.5 1] [{:fill "a"} {:fill "b"}])]
    (t/is (= 0.5 before))
    (t/is (= 1 after))))

(t/deftest the-tasks-filter-keeps-one-state-or-all
  (let [state (ptk/update (dwrv/set-task-status-filter "verified") {})]
    (t/is (= "verified" (get-in state [:workspace-review-local :task-status-filter])))
    (t/is (= :all (-> (ptk/update (dwrv/set-task-status-filter :all) state)
                      (get-in [:workspace-review-local :task-status-filter]))))))

(t/deftest every-task-state-has-its-colour
  (t/is (= ["info" "success" "warning" "purple" "error"]
           (mapv rvc/status-tone dwrv/task-statuses))))

(t/deftest the-route-names-a-branch-target-and-calls-the-root-main
  (t/is (= "Parent" (rvc/route-target {:target-name "Parent" :target-is-main false} "Main")))
  (t/is (= "Main" (rvc/route-target {:target-name "Design file" :target-is-main true} "Main")))
  (t/is (= "Main" (rvc/route-target {:target-name "Design file"} "Main"))))

(t/deftest the-activity-comes-folded-from-the-server
  (let [entry     {:id (uuid/random) :page-id (uuid/random) :kind :added}
        session   {:profile-id (uuid/random)
                   :start (js/Date.)
                   :end (js/Date.)
                   :entries [entry]}
        last-save {:created-at (js/Date.) :profile-id (uuid/random)}
        pr-id     (uuid/random)
        state     (ptk/update (activity-fetched pr-id {:entries [entry]
                                                       :sessions [session]
                                                       :people 2
                                                       :last-save last-save})
                              {})
        activity  (:workspace-review-activity state)]
    (t/is (= :loaded (:status activity)))
    (t/is (= pr-id (:pr-id activity)))
    (t/is (= [entry] (:entries activity)))
    (t/is (= [session] (:sessions activity)))
    (t/is (= 2 (:people activity)))
    (t/is (= last-save (:last-save activity)))))

;; --- Cards drawn as their sitting left the layer

(defn- rect
  [id x]
  (cts/setup-shape {:id id :type :rect :name "Card" :x x :y 20 :width 100 :height 50}))

(defn- render-card-preview
  "Markup of the drawing of a card for `layer` (`card-layer`), with each
  translation shown as its key."
  [layer]
  ;; multi-arity stub: the call site dispatches on the arity-1 property
  (with-redefs [i18n/tr (fn ([msgid] msgid) ([msgid & _] msgid))]
    (rds/renderToStaticMarkup
     (mf/element card-preview* #js {:layer layer}))))

(t/deftest a-card-draws-the-layer-as-its-sitting-left-it
  (let [id      (uuid/random)
        entry   {:id id :page-id (uuid/random) :kind :modified}
        now     {id (rect id 500)}
        saved   {id (rect id 10)}
        layer   (card-layer entry now {:revn 3 :objects saved :approximate false :deleted false})
        markup  (render-card-preview layer)]
    (t/is (= (get saved id) (:shape layer)))
    (t/is (str/includes? markup "viewBox=\"10 20 100 50\""))
    (t/is (not (str/includes? markup "viewBox=\"500 20 100 50\"")))
    (t/is (not (str/includes? markup "workspace.review.activity.drawn-as-now")))))

(t/deftest a-card-without-a-fetched-layer-draws-the-workspace-one
  (let [id    (uuid/random)
        entry {:id id :page-id (uuid/random) :kind :modified}
        now   {id (rect id 500)}]
    (t/is (= (get now id) (:shape (card-layer entry now nil))))
    (t/is (= (get now id) (:shape (card-layer entry now {:revn 3 :objects {}}))))))

(t/deftest an-approximate-card-carries-its-marker
  (let [id     (uuid/random)
        entry  {:id id :page-id (uuid/random) :kind :modified}
        now    {id (rect id 10)}
        layer  (card-layer entry now {:revn 1 :objects now :approximate true :deleted false})
        markup (render-card-preview layer)]
    (t/is (true? (:approximate layer)))
    (t/is (str/includes? markup "workspace.review.activity.drawn-as-now"))))

(t/deftest a-deleted-layer-card-draws-the-fetched-layer
  (let [id     (uuid/random)
        entry  {:id id :page-id (uuid/random) :kind :deleted}
        saved  {id (rect id 10)}
        layer  (card-layer entry {} {:revn 4 :objects saved :approximate false :deleted true})
        markup (render-card-preview layer)]
    (t/is (nil? (card-layer entry {} nil)))
    (t/is (= (get saved id) (:shape layer)))
    (t/is (str/includes? markup "viewBox=\"10 20 100 50\""))))

(t/deftest ^:async a-layer-at-a-revision-is-asked-once
  (let [calls     (atom [])
        pr-id     (uuid/random)
        params    {:pr-id pr-id
                   :branch-id (uuid/random)
                   :revn 3
                   :page-id (uuid/random)
                   :shape-id (uuid/random)}
        result    {:revn 3 :objects {} :approximate false :deleted false}
        cache-key (dwrv/layer-key pr-id 3 (:shape-id params))
        events    (atom [])]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub
                 (fn [cmd cmd-params]
                   (swap! calls conj [cmd cmd-params])
                   (rx/of result)))}
       (await (async/observe (ptk/watch (dwrv/fetch-layer-at-revision params) {} nil)
                             :on-next #(swap! events conj %)))))
    (let [state (reduce #(ptk/update %2 %1) {} @events)]
      (t/is (= [[:get-branch-layer-at-revision (dissoc params :pr-id)]] @calls))
      (t/is (= {:status :loaded :layer result}
               (get-in state [:workspace-review-layers cache-key])))
      (t/is (= :empty
               (let [seen (atom :empty)]
                 (->> (ptk/watch (dwrv/fetch-layer-at-revision params) state nil)
                      (rx/subs! #(reset! seen %)))
                 @seen))))))
