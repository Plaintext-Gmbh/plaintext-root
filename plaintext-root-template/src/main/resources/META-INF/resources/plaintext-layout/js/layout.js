/**
 * Plaintext Layout Controller
 * Open Source - No commercial dependencies
 * Provides menu toggling, theme switching, and responsive behavior.
 */
var Plaintext = Plaintext || {};

Plaintext.Layout = {
    wrapper: null,
    menuWrapper: null,
    menu: null,
    initialized: false,

    init: function() {
        if (this.initialized) return;

        this.wrapper = document.querySelector('.layout-wrapper');
        this.menuWrapper = document.querySelector('.menu-wrapper');
        this.menu = document.querySelector('.layout-menu');

        if (!this.wrapper) return;

        this.initialized = true;
        var self = this;

        // ===== Menu button (hamburger) - always visible, toggles sidebar =====
        var menuButton = document.querySelector('.menu-button');
        if (menuButton) {
            menuButton.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                if (self.isMobile()) {
                    self.wrapper.classList.toggle('layout-mobile-active');
                    document.body.classList.toggle('blocked-scroll');
                } else {
                    // Desktop: toggle sidebar static (pinned/unpinned)
                    self.wrapper.classList.toggle('layout-static');
                }
            });
        }

        // ===== Layout mask click (close mobile menu) =====
        var mask = document.querySelector('.layout-mask');
        if (mask) {
            mask.addEventListener('click', function() {
                self.wrapper.classList.remove('layout-mobile-active');
                document.body.classList.remove('blocked-scroll');
            });
        }

        // ===== Config button (gear icon) =====
        var configButton = document.getElementById('layout-config-button');
        if (configButton) {
            configButton.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                var panel = document.getElementById('layout-config');
                if (panel) {
                    panel.classList.toggle('layout-config-active');
                }
            });
        }

        // ===== Click outside to close config panel =====
        document.addEventListener('click', function(e) {
            // Don't close if clicking inside config panel
            if (e.target.closest('#layout-config') || e.target.closest('#layout-config-button')) {
                return;
            }
            var panel = document.getElementById('layout-config');
            if (panel && panel.classList.contains('layout-config-active')) {
                panel.classList.remove('layout-config-active');
            }

            // Close mobile menu on outside click
            if (!e.target.closest('.menu-wrapper') && !e.target.closest('.menu-button')) {
                self.wrapper.classList.remove('layout-mobile-active', 'layout-sidebar-active');
                document.body.classList.remove('blocked-scroll');
            }

            // Close horizontal/slim submenus on outside click
            if (!e.target.closest('.menu-wrapper') && (self.isHorizontal() || self.isSlim())) {
                var activeItems = self.menu ? self.menu.querySelectorAll('.active-menuitem') : [];
                activeItems.forEach(function(item) {
                    item.classList.remove('active-menuitem');
                });
            }
        });

        // Mark menu as ready (makes submenus visible)
        document.body.classList.add('plaintext-menu-ready');
    },

    // ==================== Mode Detection ====================

    isMobile: function() {
        return window.innerWidth < 992;
    },
    isHorizontal: function() {
        return this.wrapper && this.wrapper.classList.contains('layout-horizontal') && !this.isMobile();
    },
    isSlim: function() {
        return this.wrapper && this.wrapper.classList.contains('layout-slim') && !this.isMobile();
    },
    isStatic: function() {
        return this.wrapper && this.wrapper.classList.contains('layout-static') && !this.isMobile();
    }
};

// ==================== Configurator ====================

Plaintext.Configurator = {

    changeLayoutsTheme: function(darkMode) {
        var linkElement = document.querySelector('link[href*="layout-"]');
        if (linkElement) {
            var href = linkElement.getAttribute('href');
            var startIndex = href.indexOf('layout-') + 6;
            var endIndex = href.indexOf('.css');
            var currentSuffix = href.substring(startIndex, endIndex);
            linkElement.setAttribute('href', href.replace(currentSuffix, '-' + darkMode));
        }
    },

    changeComponentsTheme: function(themeColor, darkMode) {
        var theme = themeColor + '-' + darkMode;
        var linkElement = document.querySelector('link[href*="theme.css"]');
        if (linkElement) {
            var href = linkElement.getAttribute('href');
            var match = href.match(/(primefaces-[a-z]+-)([\w-]+)(\/theme\.css)/);
            if (match) {
                linkElement.setAttribute('href', href.replace(match[2], theme));
            }
        }
    },

    changeSectionTheme: function(theme, section) {
        var wrapper = document.querySelector('.layout-wrapper');
        if (!wrapper) return;
        var classes = wrapper.className.split(' ');
        for (var i = 0; i < classes.length; i++) {
            if (classes[i].indexOf(section + '-') === 0) {
                wrapper.classList.remove(classes[i]);
                break;
            }
        }
        wrapper.classList.add(section + '-' + theme);
    },

    changeMenuMode: function(menuMode) {
        var wrapper = document.querySelector('.layout-wrapper');
        if (!wrapper) return;
        wrapper.classList.remove('layout-sidebar', 'layout-slim', 'layout-horizontal', 'layout-static');
        wrapper.classList.add(menuMode);
        if (menuMode === 'layout-sidebar') {
            wrapper.classList.add('layout-static');
        }
    },

    updateInputStyle: function(value) {
        if (value === 'filled') {
            document.body.classList.add('ui-input-filled');
        } else {
            document.body.classList.remove('ui-input-filled');
        }
    },

    clearLayoutState: function() {
        sessionStorage.removeItem('openSubmenuLabel');
    }
};

// Alias for backward compatibility with UserPreferencesBackingBean
if (typeof PrimeFaces !== 'undefined') {
    PrimeFaces.PlaintextConfigurator = Plaintext.Configurator;
}

// ==================== Auto-init ====================
if (typeof jQuery !== 'undefined') {
    jQuery(function() { Plaintext.Layout.init(); });
} else {
    document.addEventListener('DOMContentLoaded', function() { Plaintext.Layout.init(); });
}
