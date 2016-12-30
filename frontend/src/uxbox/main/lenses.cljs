(ns uxbox.main.lenses
  (:require [lentes.core :as l]))

;; --- Workspace

(def workspace (l/key :workspace))
(def workspace-flags (comp workspace (l/key :flags)))

(def selected-shapes (comp workspace (l/key :selected)))
(def selected-page (comp workspace (l/key :page)))
(def selected-project (comp workspace (l/key :project)))
