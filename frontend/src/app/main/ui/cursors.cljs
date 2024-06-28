;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.cursors
  (:require-macros [app.main.ui.cursors :refer [cursor-ref cursor-fn collect-cursors]]))

;; Static cursors
(def ^:cursor comments (cursor-ref :comments 0 2 20))
(def ^:cursor create-artboard (cursor-ref :create-artboard))
(def ^:cursor create-ellipse (cursor-ref :create-ellipse))
(def ^:cursor create-polygon (cursor-ref :create-polygon))
(def ^:cursor create-rectangle (cursor-ref :create-rectangle))
(def ^:cursor create-shape (cursor-ref :create-shape))
(def ^:cursor duplicate (cursor-ref :duplicate 0 0 0))
(def ^:cursor hand (cursor-ref :hand))
(def ^:cursor move-pointer (cursor-ref :move-pointer))
(def ^:cursor pen (cursor-ref :pen 0 0 0))
(def ^:cursor pen-node (cursor-ref :pen-node 0 0 10 36))
(def ^:cursor pencil (cursor-ref :pencil 0 0 24))
(def ^:cursor picker (cursor-ref :picker 0 0 24))
(def ^:cursor pointer-inner (cursor-ref :pointer-inner 0 0 0))
(def ^:cursor pointer-move (cursor-ref :pointer-move 0 0 10 42))
(def ^:cursor pointer-node (cursor-ref :pointer-node 0 0 10 32))
(def ^:cursor resize-alt (cursor-ref :resize-alt))
(def ^:cursor zoom (cursor-ref :zoom))
(def ^:cursor zoom-in (cursor-ref :zoom-in))
(def ^:cursor zoom-out (cursor-ref :zoom-out))

;; Dynamic cursors
(def ^:cursor resize-ew (cursor-fn :resize-h 0))
(def ^:cursor resize-nesw (cursor-fn :resize-h 45))
(def ^:cursor resize-ns (cursor-fn :resize-h 90))
(def ^:cursor resize-nwse (cursor-fn :resize-h 135))
(def ^:cursor rotate (cursor-fn :rotate 90))
(def ^:cursor text (cursor-fn :text 0))

;; Text
(def ^:cursor scale-ew (cursor-fn :scale-h 0))
(def ^:cursor scale-nesw (cursor-fn :scale-h 45))
(def ^:cursor scale-ns (cursor-fn :scale-h 90))
(def ^:cursor scale-nwse (cursor-fn :scale-h 135))

(def ^:cursor resize-ew-2 (cursor-fn :resize-h-2 0))
(def ^:cursor resize-ns-2 (cursor-fn :resize-h-2 90))

(def default
  "A collection of all icons"
  (collect-cursors))
