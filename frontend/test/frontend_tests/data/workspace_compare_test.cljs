;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-compare-test
  "The compare mode: it opens on the branch alone, makes the workspace
  read-only while it lasts, leaves nothing behind, and applies the
  conflict choices with what the attempt that returned them carried."
  (:require
   [app.common.geom.rect :as grc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.repo :as rp]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private branch-file-id (uuid/next))
(def ^:private main-file-id (uuid/next))

(def ^:private branch
  {:id (uuid/next) :name "feature-login" :branch-file-id branch-file-id
   :source-file-id main-file-id :ahead 2})

(defn- make-store
  [state]
  (ptk/store {:state state
              :on-error (fn [cause] (t/is false (str cause)))}))

(defn- on-branch
  []
  (make-store {:current-file-id branch-file-id}))

(defn- cmd-stub
  "Fake `rp/cmd!` that records every call into `calls` and never answers,
  so a diff stays loading and a merge or an update stays in flight."
  [calls]
  (mock/stub (fn [cmd params]
               (swap! calls conj [cmd params])
               (rx/subject))))

(defn- last-call
  [calls cmd]
  (->> @calls (filter #(= cmd (first %))) (last) (second)))

(t/deftest ^:async the-mode-opens-on-the-branch-and-fetches-its-diff
  (let [calls (atom [])]
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (on-branch)]
         (ptk/emit! store (dwcp/enter branch))
         (await (async/wait-for #(some? (last-call calls :get-file-branch-diff))
                                "the diff is asked for"))
         (t/is (dwcp/comparing? @store))
         (t/is (= {:id (:id branch) :name "feature-login" :branch-file-id branch-file-id}
                  (get-in @store [:workspace-compare :branch]))
               "the mode keeps what names the branch, not its counts")
         (t/is (= :compare (get-in @store [:workspace-compare :phase])))
         (t/is (= {:branch-id (:id branch) :direction :branch->source}
                  (last-call calls :get-file-branch-diff)))
         (rx/dispose! store))))))

(t/deftest ^:async the-mode-does-not-open-with-main-open
  (let [calls (atom [])]
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (make-store {:current-file-id main-file-id})]
         (ptk/emit! store (dwcp/enter branch))
         (await (async/settle))
         (t/is (not (dwcp/comparing? @store)))
         (t/is (empty? @calls) "nothing is compared")
         (rx/dispose! store))))))

(t/deftest ^:async leaving-the-mode-leaves-nothing-behind
  (let [calls (atom [])]
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (on-branch)]
         (ptk/emit! store (dwcp/enter branch))
         (ptk/emit! store (dwb/toggle-diff-excluded #{"color//a/1"}))
         (ptk/emit! store (dwb/open-conflict-resolutions {:branch branch
                                                          :mode :merge
                                                          :conflicts [{:id (uuid/next)}]}))
         (ptk/emit! store (dwcp/exit))
         (await (async/wait-for-store store
                                      #(nil? (:workspace-branch-conflicts @store))
                                      "the attempt is dropped"))
         (t/is (not (dwcp/comparing? @store)))
         (t/is (= #{} (dwb/diff-excluded (:workspace-branch-diff @store)))
               "the selection is dropped")
         (rx/dispose! store))))))

(t/deftest ^:async the-direction-refetches-and-starts-the-list-over
  (let [calls (atom [])]
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (on-branch)]
         (ptk/emit! store (dwcp/enter branch))
         (ptk/emit! store (dwcp/set-filter :library))
         (ptk/emit! store (dwcp/select "color//a/1" {:travel false}))
         (ptk/emit! store (dwcp/set-direction :source->branch))
         (await (async/wait-for #(= :source->branch (:direction (last-call calls :get-file-branch-diff)))
                                "the other direction is asked for"))
         (t/is (= {:selected nil :filter :all :phase :compare}
                  (select-keys (:workspace-compare @store) [:selected :filter :phase])))
         (rx/dispose! store))))))

(t/deftest ^:async main-is-known-by-the-changes-of-both-directions
  (let [calls   (atom [])
        change  {:kind :shape :status :modified :id (uuid/next)}
        clash   {:kind :shape :status :conflict :id (uuid/next)}
        answers (mock/stub (fn [cmd params]
                             (swap! calls conj [cmd params])
                             (if (and (= :get-file-branch-diff cmd)
                                      (= :source->branch (:direction params)))
                               (rx/of {:changes [change] :conflicts [clash]})
                               (rx/subject))))]
    (await
     (mock/with-mocks* {rp/cmd! answers}
       (let [store   (on-branch)
             stopper (rx/subject)]
         (ptk/emit! store (dwcp/watch-events stopper))
         (ptk/emit! store (dwcp/enter branch))
         (await (async/wait-for #(some? (get-in @store [:workspace-compare :other-changes]))
                                "the other direction answers"))
         (t/is (= [:branch->source :source->branch]
                  (->> @calls
                       (filter #(= :get-file-branch-diff (first %)))
                       (mapv (comp :direction second))))
               "the diff listed, then its other direction")
         (t/is (= [change] (get-in @store [:workspace-compare :other-changes])))
         (t/is (= [clash] (get-in @store [:workspace-compare :other-conflicts]))
               "with its conflicts, which its tab counts")
         (ptk/emit! store (dwcp/set-direction :source->branch))
         (t/is (nil? (get-in @store [:workspace-compare :other-changes]))
               "the ones of the direction left do not stay")
         (t/is (nil? (get-in @store [:workspace-compare :other-conflicts])))
         (rx/push! stopper :stop)
         (rx/dispose! store))))))

(t/deftest the-panels-keep-what-the-user-set
  (let [store (make-store {:current-file-id branch-file-id
                           :workspace-compare {:branch branch :phase :compare
                                               :hidden false :collapsed #{}}})]
    (ptk/emit! store (dwcp/toggle-group "library"))
    (t/is (= #{"library"} (get-in @store [:workspace-compare :collapsed])))
    (ptk/emit! store (dwcp/toggle-group "library"))
    (t/is (= #{} (get-in @store [:workspace-compare :collapsed])))
    (ptk/emit! store (dwcp/toggle-hidden))
    (t/is (true? (get-in @store [:workspace-compare :hidden])))
    (ptk/emit! store (dwcp/set-view :highlight))
    (t/is (false? (get-in @store [:workspace-compare :hidden]))
          "picking a view shows the changes again")
    (ptk/emit! store (dwcp/set-count :source->branch 4))
    (t/is (= {:source->branch 4} (get-in @store [:workspace-compare :counts])))
    (rx/dispose! store)))

(t/deftest choosing-the-change-chosen-lets-it-go
  (let [store (make-store {:current-file-id branch-file-id
                           :workspace-compare {:phase :compare :selected nil}})]
    (ptk/emit! store (dwcp/toggle-selected "shape/p/a"))
    (t/is (= "shape/p/a" (get-in @store [:workspace-compare :selected])))
    (ptk/emit! store (dwcp/toggle-selected "shape/p/b"))
    (t/is (= "shape/p/b" (get-in @store [:workspace-compare :selected])))
    (ptk/emit! store (dwcp/toggle-selected "shape/p/b"))
    (t/is (nil? (get-in @store [:workspace-compare :selected])))
    (rx/dispose! store)))

(t/deftest the-pointer-is-followed-on-the-list-and-on-the-canvas
  (let [store (make-store {:workspace-compare {:phase :compare :selected "shape/p/a"}})
        idle  (make-store {})
        mode  #(select-keys (:workspace-compare @store) [:hovered :hovered-from :selected])]
    (ptk/emit! store (dwcp/set-hovered "shape/p/b" :canvas))
    (t/is (= {:hovered "shape/p/b" :hovered-from :canvas :selected "shape/p/a"} (mode))
          "the choice stays")
    (ptk/emit! store (dwcp/set-hovered "shape/p/c" :list))
    (ptk/emit! store (dwcp/set-hovered nil :canvas))
    (t/is (= "shape/p/c" (:hovered (mode)))
          "the canvas does not let go the row the pointer reached")
    (ptk/emit! store (dwcp/set-hovered nil :list))
    (t/is (nil? (:hovered (mode))))
    (ptk/emit! idle (dwcp/set-hovered "shape/p/b" :list))
    (t/is (not (contains? @idle :workspace-compare)) "outside the mode nothing is kept")
    (rx/dispose! store)
    (rx/dispose! idle)))

(t/deftest a-click-on-the-canvas-chooses-the-change-of-its-layer
  (let [store (make-store {:workspace-compare {:phase :compare :selected "shape/p/a"}})
        selected #(get-in @store [:workspace-compare :selected])]
    (ptk/emit! store (dwcp/set-hovered "shape/p/b" :canvas))
    (ptk/emit! store (dwcp/pick-hovered))
    (t/is (= "shape/p/b" (selected)))
    (ptk/emit! store (dwcp/set-hovered "shape/p/c" :list))
    (ptk/emit! store (dwcp/pick-hovered))
    (t/is (nil? (selected)) "a row under the pointer is no layer under it")
    (ptk/emit! store (dwcp/select "shape/p/a" {:travel false}))
    (ptk/emit! store (dwcp/set-hovered nil :list))
    (ptk/emit! store (dwcp/pick-hovered))
    (t/is (nil? (selected)) "the background lets the choice go")
    (rx/dispose! store)))

(t/deftest the-layer-chosen-is-fitted-in-the-room-the-view-leaves
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        change   {:kind :shape :status :modified :id shape-id :page-id page-id
                  :key "layer"}
        state    (fn [view]
                   {:current-file-id branch-file-id
                    :current-page-id page-id
                    :files {branch-file-id
                            {:data {:pages-index
                                    {page-id {:objects {shape-id {:id shape-id
                                                                  :selrect {:x 100 :y 100
                                                                            :width 200 :height 100}}}}}}}}
                    :workspace-local {:vport {:width 1000 :height 500}
                                      :vbox {:x 0 :y 0 :width 1000 :height 500}
                                      :zoom 1}
                    :workspace-branch-diff {:diff {:changes [change]}}
                    :workspace-compare {:phase :compare :view view
                                        :selected (dwcp/item-key change)}})
        fit      (fn [view]
                   (let [store (make-store (state view))]
                     (ptk/emit! store (dwcp/fit-selected))
                     (let [local (:workspace-local @store)]
                       (rx/dispose! store)
                       local)))
        whole    (fit :highlight)
        half     (fit :side)
        center   (fn [{:keys [vbox]} at]
                   (+ (:x vbox) (* at (:width vbox))))]
    (t/is (> (:zoom whole) 1) "the layer fills the canvas")
    (t/is (= 200 (center whole 0.5)) "in its middle")
    (t/is (< (:zoom half) (:zoom whole)) "half the canvas holds less")
    (t/is (= 200 (center half 0.75)) "in the middle of the half of the branch")
    (t/is (= (/ 1000 (:zoom half)) (get-in half [:vbox :width])))))

;;; --- The side mode ---

(def ^:private side-mode-key
  :app.main.data.workspace.compare/side-mode)

(defn- keeping-side-mode
  "Run `f` with `stored` as the side mode the browser keeps (nil for
  none), and leave what the browser kept before as it was."
  [stored f]
  (let [prev (get @storage/user side-mode-key)]
    (if (some? stored)
      (swap! storage/user assoc side-mode-key stored)
      (swap! storage/user dissoc side-mode-key))
    (try
      (f)
      (finally
        (if (some? prev)
          (swap! storage/user assoc side-mode-key prev)
          (swap! storage/user dissoc side-mode-key))))))

(defn- side-store
  [view]
  (make-store {:current-file-id branch-file-id
               :workspace-compare {:branch branch :phase :compare
                                   :view view :side-mode :linked}}))

(defn- side-mode
  [store]
  (get-in @store [:workspace-compare :side-mode]))

(t/deftest ^:async the-side-mode-starts-linked-and-comes-back-as-picked
  (let [calls (atom [])
        prev  (get @storage/user side-mode-key)]
    (swap! storage/user dissoc side-mode-key)
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (on-branch)]
         (ptk/emit! store (dwcp/enter branch))
         (t/is (= :linked (side-mode store)) "linked with nothing kept")
         (ptk/emit! store (dwcp/set-side-mode :aligned))
         (ptk/emit! store (dwcp/exit))
         (ptk/emit! store (dwcp/enter branch))
         (await (async/wait-for #(some? (last-call calls :get-file-branch-diff))
                                "the diff is asked for"))
         (t/is (= :aligned (side-mode store)) "the one picked last")
         (rx/dispose! store))))
    (if (some? prev)
      (swap! storage/user assoc side-mode-key prev)
      (swap! storage/user dissoc side-mode-key))))

(t/deftest ^:async the-side-mode-kept-must-be-one
  (let [calls (atom [])
        prev  (get @storage/user side-mode-key)]
    (swap! storage/user assoc side-mode-key :sideways)
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (on-branch)]
         (ptk/emit! store (dwcp/enter branch))
         (await (async/wait-for #(some? (last-call calls :get-file-branch-diff))
                                "the diff is asked for"))
         (t/is (= :linked (side-mode store)))
         (rx/dispose! store))))
    (if (some? prev)
      (swap! storage/user assoc side-mode-key prev)
      (swap! storage/user dissoc side-mode-key))))

(t/deftest ^:async resolving-takes-the-side-mode-kept
  (let [calls (atom [])
        prev  (get @storage/user side-mode-key)]
    (swap! storage/user assoc side-mode-key :aligned)
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (on-branch)]
         (ptk/emit! store (dwb/open-conflict-resolutions {:branch branch
                                                          :mode :merge
                                                          :conflicts [{:id (uuid/next)}]}))
         (ptk/emit! store (dwcp/start-resolve))
         (await (async/wait-for #(some? (last-call calls :get-file-branch-diff))
                                "the diff is asked for"))
         (t/is (= :aligned (side-mode store)))
         (rx/dispose! store))))
    (if (some? prev)
      (swap! storage/user assoc side-mode-key prev)
      (swap! storage/user dissoc side-mode-key))))

(t/deftest the-side-mode-is-kept-in-the-browser
  (keeping-side-mode
   nil
   (fn []
     (let [store (side-store :side)]
       (ptk/emit! store (dwcp/set-side-mode :aligned))
       (t/is (= :aligned (side-mode store)))
       (t/is (= :aligned (get @storage/user side-mode-key)))
       (rx/dispose! store)))))

(t/deftest ^:async the-key-flips-the-side-mode-beside-the-branch-alone
  (let [prev (get @storage/user side-mode-key)
        side (side-store :side)
        flat (side-store :highlight)]
    (ptk/emit! side (dwcp/toggle-side-mode))
    (await (async/wait-for-store side #(= :aligned (side-mode side)) "it flips"))
    (ptk/emit! side (dwcp/toggle-side-mode))
    (await (async/wait-for-store side #(= :linked (side-mode side)) "and back"))
    (ptk/emit! flat (dwcp/toggle-side-mode))
    (await (async/settle))
    (t/is (= :linked (side-mode flat)) "highlighting, it does nothing")
    (rx/dispose! side)
    (rx/dispose! flat)
    (if (some? prev)
      (swap! storage/user assoc side-mode-key prev)
      (swap! storage/user dissoc side-mode-key))))

(t/deftest another-view-keeps-the-side-mode
  (let [store (make-store {:current-file-id branch-file-id
                           :workspace-compare {:branch branch :phase :compare
                                               :view :side :side-mode :aligned}})]
    (ptk/emit! store (dwcp/set-view :highlight))
    (ptk/emit! store (dwcp/set-view :side))
    (t/is (= :aligned (side-mode store)))
    (rx/dispose! store)))

;;; --- Zoom with main beside the branch ---

(defn- zoom-state
  "A page with a board at 100,100 (200 by 100) on a viewport of 1000 by
  500, with the compare mode in `view` when there is one."
  [view local]
  (let [page-id  (uuid/next)
        board-id (uuid/next)]
    (cond-> {:current-file-id branch-file-id
             :current-page-id page-id
             :files {branch-file-id
                     {:data {:pages-index
                             {page-id
                              {:objects
                               {uuid/zero {:id uuid/zero :type :frame :parent-id uuid/zero
                                           :shapes [board-id]}
                                board-id  (cts/setup-rect {:id board-id :type :frame
                                                           :parent-id uuid/zero
                                                           :x 100 :y 100
                                                           :width 200 :height 100})}}}}}}
             :workspace-local (merge {:vport {:width 1000 :height 500}
                                      :vbox (grc/make-rect 0 0 1000 500)
                                      :zoom 1}
                                     local)}
      (some? view)
      (assoc :workspace-compare {:phase :compare :view view}))))

(defn- zoom-with
  [state event]
  (let [store (make-store state)]
    (ptk/emit! store event)
    (let [local (:workspace-local @store)]
      (rx/dispose! store)
      local)))

(defn- vbox-at
  "The point of the page at `at` of the width of the viewbox."
  [{:keys [vbox]} at]
  (+ (:x vbox) (* at (:width vbox))))

(t/deftest the-whole-page-is-fitted-in-the-room-the-view-leaves
  (let [whole (zoom-with (zoom-state nil nil) dwz/zoom-to-fit-all)
        half  (zoom-with (zoom-state :side nil) dwz/zoom-to-fit-all)]
    (t/is (= 200 (vbox-at whole 0.5)) "in the middle of the canvas")
    (t/is (= 200 (vbox-at half 0.75)) "in the middle of the half of the branch")
    (t/is (< (:zoom half) (:zoom whole)) "half the canvas holds less")
    (t/is (= 200 (vbox-at (zoom-with (zoom-state :highlight nil) dwz/zoom-to-fit-all) 0.5))
          "highlighting, the canvas is not split")))

(t/deftest the-whole-page-takes-in-main-beside-the-branch
  (let [state (assoc-in (zoom-state :side nil) [:workspace-compare :main-extent]
                        (grc/make-rect 1000 100 200 100))
        local (zoom-with state dwz/zoom-to-fit-all)]
    (t/is (= 650 (vbox-at local 0.75)) "the middle of the board on both sides")))

(t/deftest the-zoom-turns-on-the-middle-of-the-room-the-view-leaves
  (let [local {:zoom 2 :vbox (grc/make-rect 0 0 500 250)}
        whole (zoom-with (zoom-state nil local) dwz/reset-zoom)
        half  (zoom-with (zoom-state :side local) dwz/reset-zoom)
        more  (zoom-with (zoom-state :side local) (dwz/increase-zoom nil))]
    (t/is (= 1 (:zoom half)))
    (t/is (= 250 (vbox-at whole 0.5)) "the middle of the canvas stays")
    (t/is (= 375 (vbox-at half 0.75)) "the middle of the half of the branch stays")
    (t/is (= 375 (vbox-at more 0.75)) "zooming in from the menu too")))

(t/deftest the-events-do-nothing-outside-the-mode
  (let [store (make-store {:current-file-id branch-file-id})]
    (keeping-side-mode
     nil
     (fn []
       (ptk/emit! store (dwcp/set-side-mode :aligned))
       (ptk/emit! store (dwcp/toggle-side-mode))
       (t/is (nil? (get @storage/user side-mode-key)) "nothing is kept")))
    (ptk/emit! store (dwcp/toggle-hidden))
    (ptk/emit! store (dwcp/set-filter :library))
    (ptk/emit! store (dwcp/toggle-group "library"))
    (ptk/emit! store (dwcp/set-count :source->branch 4))
    (t/is (not (dwcp/comparing? @store)) "none of them creates the mode")
    (rx/dispose! store)))

;;; --- Entries ---

(t/deftest an-entry-is-known-by-its-change-key
  (t/is (= "shape/p/1" (dwcp/item-key {:key "shape/p/1" :kind :shape :id 1})))
  (t/is (= "token:brand" (dwcp/item-key {:kind :token :id "brand"}))
        "an entry without a change key falls back to its kind and id"))

(t/deftest a-layer-is-placed-by-the-open-file-or-by-the-diff
  (let [id      (uuid/next)
        rect    {:x 1 :y 2 :width 3 :height 4}
        objects {id {:id id :selrect rect}}]
    (t/is (= rect (dwcp/item-rect objects {:kind :shape :id id})))
    (t/is (= rect (dwcp/item-rect {} {:kind :shape :id id :shape {:selrect rect}}))
          "a layer the open file lacks comes whole in the diff")
    (t/is (= rect (dwcp/item-rect {} {:kind :shape :id id :shape rect}))
          "its own position and size do when it carries no selrect")
    (t/is (nil? (dwcp/item-rect objects {:kind :color :id id})))
    (t/is (nil? (dwcp/item-rect {} {:kind :shape :id id})))))

(t/deftest the-conflicts-of-an-attempt-win-over-the-ones-of-the-diff
  (let [branch-id (uuid/next)
        diff-st   {:diff {:conflicts [{:id :from-diff}]}}]
    (t/is (= [{:id :from-attempt}]
             (dwcp/get-conflicts {:branch-id branch-id :conflicts [{:id :from-attempt}]}
                                 diff-st branch-id)))
    (t/is (= [{:id :from-diff}]
             (dwcp/get-conflicts {:branch-id (uuid/next) :conflicts [{:id :other}]}
                                 diff-st branch-id))
          "the attempt of another branch does not count")
    (t/is (= [{:id :from-diff}] (dwcp/get-conflicts nil diff-st branch-id)))))

;;; --- Read-only ---

(t/deftest ^:async no-edit-is-committed-while-comparing
  (let [state {:current-file-id branch-file-id
               :permissions {:can-edit true}
               :files {branch-file-id {:id branch-file-id :revn 1 :vern 0}}
               :workspace-compare {:branch branch :phase :compare}}
        store (make-store state)
        commits (atom [])
        sub   (->> (ptk/input-stream store)
                   (rx/filter dch/commit?)
                   (rx/subs! #(swap! commits conj %)))]
    (ptk/emit! store (dch/commit-changes {:redo-changes [{:type :mod-obj}]
                                          :undo-changes []
                                          :file-id branch-file-id}))
    (await (async/settle))
    (t/is (empty? @commits) "the comparison refuses the edit")
    (rx/dispose! sub)
    (rx/dispose! store)))

;;; --- Resolving conflicts ---

(t/deftest ^:async resolving-starts-in-the-direction-of-the-attempt
  (let [calls (atom [])]
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (on-branch)]
         (ptk/emit! store (dwb/open-conflict-resolutions {:branch branch
                                                          :mode :update
                                                          :conflicts [{:id (uuid/next)}]}))
         (ptk/emit! store (dwcp/start-resolve))
         (await (async/wait-for #(some? (last-call calls :get-file-branch-diff))
                                "the diff is asked for"))
         (t/is (= :resolve (get-in @store [:workspace-compare :phase])))
         (t/is (= (:id branch) (get-in @store [:workspace-compare :branch :id])))
         (t/is (= :source->branch (:direction (last-call calls :get-file-branch-diff)))
               "an update resolves main's changes")
         (rx/dispose! store))))))

(t/deftest ^:async conflicts-of-another-file-wait-for-their-branch
  (let [calls (atom [])]
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (make-store {:current-file-id main-file-id})]
         (ptk/emit! store (dwb/open-conflict-resolutions {:branch branch
                                                          :mode :merge
                                                          :conflicts [{:id (uuid/next)}]}))
         (ptk/emit! store (dwcp/start-resolve))
         (await (async/wait-for-store store
                                      #(true? (get-in @store [:workspace-branch-conflicts :pending]))
                                      "the conflicts wait"))
         (t/is (not (dwcp/comparing? @store)) "the mode waits for the branch file")
         (rx/dispose! store))))))

(t/deftest going-back-keeps-the-choices-and-drops-the-attempt
  (let [conflict-id (uuid/next)
        store (make-store {:current-file-id branch-file-id
                           :workspace-compare {:branch branch :phase :resolve :selected "x"}
                           :workspace-branch-diff {:branch-id (:id branch)
                                                   :resolutions {conflict-id :source}}
                           :workspace-branch-conflicts {:branch-id (:id branch)
                                                        :conflicts [{:id conflict-id}]}})]
    (ptk/emit! store (dwcp/back-to-compare))
    (t/is (= {:phase :compare :selected nil}
             (select-keys (:workspace-compare @store) [:phase :selected])))
    (t/is (nil? (:workspace-branch-conflicts @store)))
    (t/is (= {conflict-id :source} (get-in @store [:workspace-branch-diff :resolutions])))
    (rx/dispose! store)))

(defn- resolving
  "A store that resolves one conflict of the branch, with a choice made
  for it and for a conflict that is no longer there."
  [conflict-id diff-st attempt]
  (make-store {:current-file-id branch-file-id
               :workspace-compare {:branch (select-keys branch [:id :name :branch-file-id])
                                   :phase :resolve}
               :workspace-branch-diff
               (merge {:branch-id (:id branch)
                       :status :loaded
                       :diff {:conflicts [{:id conflict-id}] :meta {:source-revn 9}}
                       :resolutions {conflict-id :branch (uuid/next) :source}}
                      diff-st)
               :workspace-branch-conflicts attempt}))

(t/deftest ^:async applying-a-merge-sends-what-the-attempt-carried
  (let [calls       (atom [])
        conflict-id (uuid/next)]
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (resolving conflict-id
                              {:direction :branch->source
                               :excluded {:branch->source #{"from-the-comparison"}}}
                              {:branch-id (:id branch)
                               :mode :merge
                               :conflicts [{:id conflict-id}]
                               :source-revn 7
                               :excluded #{"from-the-attempt"}
                               :comment "Ships the header"})]
         (ptk/emit! store (dwcp/apply-resolutions))
         (await (async/wait-for #(some? (last-call calls :merge-file-branch))
                                "the merge is asked for"))
         (t/is (= {:branch-id (:id branch)
                   :resolutions {conflict-id :branch}
                   :excluded #{"from-the-attempt"}
                   :comment "Ships the header"
                   :expected-source-revn 9}
                  (last-call calls :merge-file-branch))
               "only the choices of its conflicts travel, with its selection and comment")
         (rx/dispose! store))))))

(t/deftest ^:async applying-an-update-from-the-comparison-sends-its-selection
  (let [calls       (atom [])
        conflict-id (uuid/next)]
    (await
     (mock/with-mocks* {rp/cmd! (cmd-stub calls)}
       (let [store (resolving conflict-id
                              {:direction :source->branch
                               :excluded {:source->branch #{"left-out"}
                                          :branch->source #{"other-direction"}}}
                              nil)]
         (ptk/emit! store (dwcp/apply-resolutions))
         (await (async/wait-for #(some? (last-call calls :update-file-branch-from-source))
                                "the update is asked for"))
         (t/is (= {:branch-id (:id branch)
                   :resolutions {conflict-id :branch}
                   :excluded #{"left-out"}
                   :expected-source-revn 9}
                  (last-call calls :update-file-branch-from-source)))
         (t/is (nil? (last-call calls :merge-file-branch)))
         (rx/dispose! store))))))
