(ns uxbox.cli.sql
  (:require [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "sql/cli.sql" {:quoting :ansi :fn-suffix ""})
