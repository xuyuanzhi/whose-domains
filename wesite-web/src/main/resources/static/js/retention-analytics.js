(function (root) {
    'use strict';

    var EVENTS = ['watch_created', 'notification_opened', 'notification_action_clicked',
        'notification_preferences_saved', 'watchlist_return_visit', 'domain_detail_cta_clicked'];
    var TYPES = ['watch_created', 'notification_opened', 'notification_action_clicked',
        'notification_preferences_saved', 'watchlist_return_visit', 'mark_read', 'delete', 'mark_all_read',
        'open_evidence', 'monitor_domain'];
    var CATEGORIES = ['all', 'expiry', 'security', 'domain-change', 'investment',
        'watchlist', 'notifications', 'preferences'];
    var RISKS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN'];
    var SOURCES = ['watchlist', 'notification_center', 'notification_settings', 'domain_detail'];

    function hasValue(values, value) {
        return typeof value === 'string' && values.indexOf(value) >= 0;
    }

    function safeParameters(parameters) {
        var input = parameters || {};
        var safe = {};
        if (hasValue(TYPES, input.type)) safe.type = input.type;
        if (hasValue(CATEGORIES, input.category)) safe.category = input.category;
        if (hasValue(RISKS, input.risk)) safe.risk = input.risk;
        if (hasValue(SOURCES, input.source)) safe.source = input.source;
        return safe;
    }

    function track(eventName, parameters) {
        if (!hasValue(EVENTS, eventName) || typeof root.gtag !== 'function') return false;
        try {
            root.gtag('event', eventName, safeParameters(parameters));
            return true;
        } catch (error) {
            return false;
        }
    }

    root.WhoseRetentionAnalytics = { track: track };
})(window);
