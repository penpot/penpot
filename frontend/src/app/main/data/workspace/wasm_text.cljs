;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.wasm-text
  "Helpers/events to resize wasm text shapes without depending on workspace.texts.

  This exists to avoid circular deps:
  workspace.texts -> workspace.libraries -> workspace.texts"
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.modifiers :as ctm]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.modifiers :as dwm]
   [app.render-wasm.api :as wasm.api]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn resize-wasm-text-modifiers
  ([shape]
   (resize-wasm-text-modifiers shape (:content shape)))

  ([{:keys [id points selrect grow-type] :as shape} content]
   (wasm.api/use-shape id)
   (wasm.api/set-shape-text-content id content)
   (wasm.api/set-shape-text-images id content)

   (let [dimension (wasm.api/get-text-dimensions)
         width-scale (if (#{:fixed :auto-height} grow-type)
                       1.0
                       (/ (:width dimension) (:width selrect)))
         height-scale (if (= :fixed grow-type)
                        1.0
                        (/ (:height dimension) (:height selrect)))
         resize-v  (gpt/point width-scale height-scale)
         origin    (first points)]

     {id
      {:modifiers
       (ctm/resize-modifiers
        resize-v
        origin
        (:transform shape (gmt/matrix))
        (:transform-inverse shape (gmt/matrix)))}})))

(defn resize-wasm-text
  "Resize a single text shape (auto-width/auto-height) by id.
  No-op if the id is not a text shape or is :fixed."
  [id]
  (ptk/reify ::resize-wasm-text
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            shape   (get objects id)]
        (if (and (some? shape)
                 (cfh/text-shape? shape)
                 (not= :fixed (:grow-type shape)))
          (rx/of (dwm/apply-wasm-modifiers (resize-wasm-text-modifiers shape)))
          (rx/empty))))))

(defn resize-wasm-text-all
  "Resize all text shapes (auto-width/auto-height) from a collection of ids."
  [ids]
  (ptk/reify ::resize-wasm-text-all
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rx/from ids)
           (rx/map resize-wasm-text)))))
