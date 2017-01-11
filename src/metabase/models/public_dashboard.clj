(ns metabase.models.public-dashboard
  (:require [metabase.models.interface :as i]
            [metabase.util :as u]))

(i/defentity PublicDashboard :public_dashboard)

(defn- pre-insert [public-dashboard]
  (merge {:uuid (str (java.util.UUID/randomUUID))}
         public-dashboard))

(u/strict-extend (class PublicDashboard)
  i/IEntity
  (merge i/IEntityDefaults
         {:pre-insert pre-insert}))
