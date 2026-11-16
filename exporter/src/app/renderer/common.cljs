;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.renderer.common
  "Helpers shared by the browser-based exporters."
  (:require
   [app.browser :as bw]
   [app.common.exceptions :as ex]
   [promesa.core :as p]))

(def ^:private min-render-size 0.01)
(def ^:private size-epsilon 1e-6)

(defn- too-small?
  [value]
  (<= value (+ min-render-size size-epsilon)))

(defn- assert-renderable-size
  "Fails with a validation error when a render box has no drawable area.

  A degenerate box (the 0.01 sentinel rect) is treated as hidden by Playwright,
  so waiting for it to become visible ends in a generic timeout. Check the box
  explicitly after the node is attached and report the object that failed."
  [width height {:keys [id name]}]
  (when (or (too-small? width) (too-small? height))
    (ex/raise :type :validation
              :code :empty-object
              :hint (str "object \"" (or name id) "\" has an empty render box ("
                         width "x" height " px)")
              :object-id id
              :object-name name
              :width width
              :height height)))

(defn wait-for-object
  "Waits for the screenshot node of `object` and returns its locator.

  The node is first waited for in the attached state, then its render box is
  checked, and only then is it waited for in the visible state. This turns an
  empty box into an explicit error naming the object instead of a visibility
  timeout."
  [page {:keys [id] :as object}]
  (let [node (bw/select page (str "#screenshot-" id))]
    (p/let [_    (bw/wait-for node {:state "attached"})
            rect (bw/eval! node (fn [elem] (.getBoundingClientRect ^js elem)))]
      (assert-renderable-size (.-width ^js rect) (.-height ^js rect) object)
      (bw/wait-for node)
      node)))
