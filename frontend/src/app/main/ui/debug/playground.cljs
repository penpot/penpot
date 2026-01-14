(ns app.main.ui.debug.playground
  #_(:require-macros [app.main.style :as stl])
  (:require
   [app.util.clipboard :as clipboard]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(mf/defc playground-clipboard
  {::mf/wrap-props false
   ::mf/private true}
  []
  (let [on-paste (mf/use-fn
                  (fn [e]
                    (let [stream (clipboard/from-clipboard-event e)]
                      (rx/sub! stream
                               (fn [data]
                                 (js/console.log "data" data))))))

        on-dragover (mf/use-fn
                     (fn [e]
                       (.preventDefault e)))

        on-drop (mf/use-fn
                 (fn [e]
                   (.preventDefault e)
                   (let [stream (clipboard/from-drop-event e)]
                     (rx/sub! stream
                              (fn [data]
                                (js/console.log "data" data))))))

        on-click (mf/use-fn
                  (fn [e]
                    (js/console.log "event" e)
                    (let [stream (clipboard/from-navigator)]
                      (rx/sub! stream
                               (fn [data]
                                 (js/console.log "data" data))))))]

    (.addEventListener js/window "paste" on-paste)
    (.addEventListener js/window "drop" on-drop)
    (.addEventListener js/window "dragover" on-dragover)

    [:button#paste {:on-click on-click} "Paste"]))

(mf/defc playground
  {::mf/wrap-props false
   ::mf/private true}
  []
  [:& playground-clipboard])


