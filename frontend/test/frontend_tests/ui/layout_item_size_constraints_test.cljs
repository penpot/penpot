;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.layout-item-size-constraints-test
  "The minimum and maximum size fields of the layout element menu: shown
  on the axes that fill their parent or fit their content, for a board
  at the top level as for a layout child, and editing one updates that
  constraint of the selected shape."
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/client" :as rdc]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.store :as st]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :as layout-item]
   [app.util.globals :as globals]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [rumext.v2 :as mf]))

(def ^:private constraint-attrs
  ["layout-item-min-w" "layout-item-max-w" "layout-item-min-h" "layout-item-max-h"])

(defn- shown-constraints
  "The constraint fields shown in `container`."
  [container]
  (into #{}
        (filter #(.querySelector container (str "input[data-attr='" % "']")))
        constraint-attrs))

(defn- ^:async with-mounted-menu
  "Mounts the layout element menu of a board `:shape`, at the top level
  of the page or inside a flex board (`:parent?`), and
  calls `test-fn` with its container and the events it emits, in an
  atom. `update-layout-child` returns its arguments instead of the event."
  [{:keys [shape parent?]} test-fn]
  (let [dom          (JSDOM. "<!doctype html><html><body><div id='root'></div></body></html>")
        window       (.-window dom)
        document     (.-document window)
        old-window   (.-window js/globalThis)
        old-document (.-document js/globalThis)
        old-act      (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        old-state    @st/state
        file-id      (uuid/next)
        page-id      (uuid/next)
        parent       {:id (uuid/next) :type :frame :layout :flex :layout-flex-dir :row}
        shape        (cond-> shape parent? (assoc :parent-id (:id parent)))
        objects      (cond-> {(:id shape) shape} parent? (assoc (:id parent) parent))
        container    (.getElementById document "root")
        emitted      (atom [])]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (reset! st/state {:current-file-id file-id :current-page-id page-id
                      :files {file-id {:id file-id
                                       :data {:pages-index {page-id {:objects objects}}}}}})
    (try
      (await
       (mock/with-mocks* {globals/document document
                          globals/window window
                          st/emit! (fn ([] nil)
                                     ([event] (swap! emitted conj event) nil)
                                     ([event & events] (swap! emitted into (cons event events)) nil))
                          dwsl/update-layout-child (fn ([ids attrs] {:ids ids :attrs attrs})
                                                     ([ids attrs _options] {:ids ids :attrs attrs}))}
         (let [root (rdc/createRoot container)]
           (try
             (await (react/act
                     #(.render root (mf/element layout-item/layout-item-menu*
                                                #js {:ids [(:id shape)]
                                                     :type :frame
                                                     :values shape
                                                     :isLayoutChild (boolean parent?)
                                                     :isFlexParent (boolean parent?)
                                                     :isFlexLayout true
                                                     :isLayoutContainer true}))))
             (await (test-fn container emitted))
             (finally
               (await (react/act #(.unmount root))))))))
      (finally
        (reset! st/state old-state)
        (.close window)
        (set! (.-window js/globalThis) old-window)
        (set! (.-document js/globalThis) old-document)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) old-act)))))

(defn- board
  [h-sizing v-sizing]
  {:id (uuid/next)
   :type :frame
   :layout :flex
   :layout-item-h-sizing h-sizing
   :layout-item-v-sizing v-sizing})

(t/deftest ^:async fit-content-board-shows-its-constraints
  (doseq [parent? [false true]]
    (await
     (with-mounted-menu
       {:shape (board :auto :fix) :parent? parent?}
       (fn [container _]
         (t/testing (str "fit content width, " (if parent? "layout child" "top level"))
           (t/is (= #{"layout-item-min-w" "layout-item-max-w"}
                    (shown-constraints container)))))))
    (await
     (with-mounted-menu
       {:shape (board :fix :auto) :parent? parent?}
       (fn [container _]
         (t/testing (str "fit content height, " (if parent? "layout child" "top level"))
           (t/is (= #{"layout-item-min-h" "layout-item-max-h"}
                    (shown-constraints container)))))))
    (await
     (with-mounted-menu
       {:shape (board :fix :fix) :parent? parent?}
       (fn [container _]
         (t/testing (str "fixed size, " (if parent? "layout child" "top level"))
           (t/is (= #{} (shown-constraints container)))
           (t/is (nil? (.querySelector container "[aria-label='Layout item size constraints']")))))))))

(t/deftest ^:async fill-child-keeps-its-constraints
  (await
   (with-mounted-menu
     {:shape (board :fill :fill) :parent? true}
     (fn [container _]
       (t/is (= (set constraint-attrs) (shown-constraints container)))))))

(defn- react-props
  "The props React rendered `node` with."
  [node]
  (some->> (js/Object.keys node)
           (filter #(.startsWith % "__reactProps$"))
           (first)
           (unchecked-get node)))

(defn- ^:async type-value!
  "Types `value` in the constraint field `attr`, presses Enter and leaves
  it, which validates it. The input's handlers are called directly:
  react-dom, loaded before the jsdom document exists, handles the input
  events with a polyfill for old browsers that fails in jsdom."
  [container attr value]
  (let [input (.querySelector container (str "input[data-attr='" attr "']"))
        props (react-props input)]
    (set! (.-value input) value)
    (await (react/act #((.-onKeyDown props) #js {:key "Enter" :target input})))
    (await (react/act #((.-onBlur props) #js {:target input})))))

(t/deftest ^:async editing-a-constraint-updates-the-selected-board
  (doseq [parent? [false true]
          [h-sizing v-sizing attr key] [[:auto :fix "layout-item-max-w" :layout-item-max-w]
                                        [:fix :auto "layout-item-min-h" :layout-item-min-h]]]
    (let [shape (board h-sizing v-sizing)]
      (await
       (with-mounted-menu
         {:shape shape :parent? parent?}
         (^:async fn [container emitted]
           (await (type-value! container attr "120"))
           (t/testing (str attr ", " (if parent? "layout child" "top level"))
             (t/is (= [{:ids [(:id shape)] :attrs {key 120}}] @emitted)))))))))
