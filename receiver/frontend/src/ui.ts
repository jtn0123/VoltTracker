/**
 * VoltTracker - UI Module
 * Theme, date picker, navigation, and general UI initialization
 */

import { DEBUG, state, store } from '@/core';
import { loadTrips } from '@/trips';
import { Theme } from '@/types/enums';

/**
 * Initialize theme from localStorage
 */
export function initTheme(): void {
  const savedTheme = (localStorage.getItem('theme') as Theme) || Theme.Dark;
  document.documentElement.dataset.theme = savedTheme;
  updateThemeIcon(savedTheme);

  const themeBtn = document.querySelector('.theme-toggle');
  if (themeBtn) {
    themeBtn.setAttribute('aria-pressed', savedTheme === Theme.Dark ? 'true' : 'false');
  }
}

/**
 * Toggle between light and dark theme
 */
export function toggleTheme(): void {
  const currentTheme = document.documentElement.dataset.theme;
  const newTheme = currentTheme === Theme.Dark ? Theme.Light : Theme.Dark;
  document.documentElement.dataset.theme = newTheme;
  localStorage.setItem('theme', newTheme);
  updateThemeIcon(newTheme);

  // Update the reactive store so subscribers (e.g. chart re-renders) stay in sync
  store.setState({ theme: newTheme });

  const themeBtn = document.querySelector('.theme-toggle');
  if (themeBtn) {
    themeBtn.setAttribute('aria-pressed', newTheme === Theme.Dark ? 'true' : 'false');
  }
}

/**
 * Update theme icon
 */
export function updateThemeIcon(theme: string): void {
  const icon = document.getElementById('theme-icon');
  if (icon) icon.textContent = theme === Theme.Dark ? '🌙' : '☀️';
}

/**
 * Initialize date range picker
 */
export function initDatePicker(): void {
  const input = document.getElementById('date-range');
  if (!input) return;

  state.flatpickrInstance = flatpickr(input, {
    mode: 'range',
    dateFormat: 'M j, Y',
    theme: 'dark',
    onChange: function (selectedDates: Date[]) {
      if (selectedDates.length === 2) {
        state.dateFilter.start = selectedDates[0].toISOString().split('T')[0];
        state.dateFilter.end = selectedDates[1].toISOString().split('T')[0];
        const clearBtn = document.getElementById('clear-date-filter');
        if (clearBtn) clearBtn.style.display = 'block';
        loadTrips();
      }
    },
  });
}

/**
 * Clear date filter
 */
export function clearDateFilter(): void {
  state.dateFilter = { start: null, end: null };
  if (state.flatpickrInstance) state.flatpickrInstance.clear();
  const clearBtn = document.getElementById('clear-date-filter');
  if (clearBtn) clearBtn.style.display = 'none';
  loadTrips();
}

/**
 * Toggle export dropdown menu
 */
export function toggleExportMenu(): void {
  const menu = document.getElementById('export-menu');
  const btn = document.getElementById('export-btn');
  if (!menu) return;

  const isOpen = menu.classList.toggle('show');
  if (btn) btn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
}

interface NavSection {
  id: string;
  nav: string;
}

const PAGE_TITLES: Record<string, string> = {
  summary: 'Dashboard',
  live: 'Live Drive',
  trips: 'Trips',
  map: 'GPS Map',
  battery: 'Battery',
  charging: 'Charging',
  analysis: 'Insights',
  settings: 'Settings',
};

function showRoutedSection(sectionId: string): HTMLElement | null {
  const section = document.getElementById(sectionId);
  if (sectionId === 'live-trip-section' && section) {
    section.style.display = 'block';
    section.dataset.routeVisible = 'true';
    const statusPill = document.getElementById('live-status-pill');
    if (statusPill) statusPill.textContent = 'Live';
  }
  return section;
}

function setRouteHash(href: string): void {
  if (globalThis.location.hash === href) return;
  globalThis.history.pushState(null, '', href);
}

/**
 * Initialize bottom navigation with scroll spy
 */
export function initBottomNav(): void {
  const bottomNav = document.querySelector('.bottom-nav');
  const appNav = document.querySelector('.app-nav');
  if (!bottomNav && !appNav) return;

  const navItems = document.querySelectorAll('.bottom-nav-item, .app-nav-item');
  const sections: NavSection[] = [
    { id: 'summary-section', nav: 'summary' },
    { id: 'live-trip-section', nav: 'live' },
    { id: 'battery-health-section', nav: 'battery' },
    { id: 'trips-section', nav: 'trips' },
    { id: 'soc-section', nav: 'analysis' },
    { id: 'charging-section', nav: 'charging' },
  ];

  navItems.forEach((item) => {
    item.addEventListener('click', (e) => {
      const anchor = item as HTMLAnchorElement;
      const href = anchor.getAttribute('href') || '';
      if (!href.startsWith('#')) return;

      e.preventDefault();
      const sectionId = href.substring(1);
      if (!sectionId) return;
      setRouteHash(href);
      let section = showRoutedSection(sectionId);
      if (!section || section.offsetParent === null) {
        section = sectionId === 'battery-health-section'
          ? document.getElementById('soc-section')
          : document.getElementById('summary-section');
      }
      if (section) {
        const headerOffset = 80;
        const elementPosition = section.getBoundingClientRect().top;
        const offsetPosition = elementPosition + globalThis.pageYOffset - headerOffset;

        globalThis.scrollTo({ top: offsetPosition, behavior: 'smooth' });
        setActiveNavItem((item as HTMLElement).dataset.section || '');
      }
    });
  });

  const activateHash = (): void => {
    const hash = globalThis.location.hash;
    if (!hash) {
      setActiveNavItem('summary');
      return;
    }
    const sectionId = hash.substring(1);
    const navItem = document.querySelector<HTMLElement>(
      `.bottom-nav-item[href="${hash}"], .app-nav-item[href="${hash}"]`,
    );
    const section = showRoutedSection(sectionId);
    if (navItem) setActiveNavItem(navItem.dataset.section || '');
    if (section && section.offsetParent !== null) {
      const headerOffset = 80;
      const elementPosition = section.getBoundingClientRect().top;
      const offsetPosition = elementPosition + globalThis.pageYOffset - headerOffset;
      globalThis.scrollTo({ top: offsetPosition, behavior: 'auto' });
    }
  };

  activateHash();
  globalThis.addEventListener('hashchange', activateHash);

  // Scroll handler registration is deferred to initScrollHandlers()
  _pendingNavSections = sections;
}

let _pendingNavSections: NavSection[] | null = null;

/**
 * Update active nav item based on scroll position
 */
export function updateActiveNavOnScroll(sections: NavSection[]): void {
  const scrollPosition = globalThis.scrollY + 100;

  for (let i = sections.length - 1; i >= 0; i--) {
    const section = document.getElementById(sections[i].id);
    if (section && section.offsetParent !== null && section.offsetTop <= scrollPosition) {
      setActiveNavItem(sections[i].nav);
      break;
    }
  }
}

/**
 * Set active navigation item
 */
export function setActiveNavItem(sectionName: string): void {
  if (sectionName) {
    document.body.dataset.activeSection = sectionName;
  }

  const navItems = document.querySelectorAll('.bottom-nav-item, .app-nav-item');
  navItems.forEach((item) => {
    const el = item as HTMLElement;
    if (el.dataset.section === sectionName) {
      item.classList.add('active');
    } else {
      item.classList.remove('active');
    }
  });

  const pageTitle = document.getElementById('page-title');
  if (pageTitle && PAGE_TITLES[sectionName]) {
    pageTitle.textContent = PAGE_TITLES[sectionName];
  }
}

/**
 * Add scroll shadow to header (sets up via shared scroll handler)
 */
export function initHeaderScroll(): void {
  // Handled by initScrollHandlers()
}

/**
 * Initialize back to top button (sets up via shared scroll handler)
 */
export function initBackToTop(): void {
  const backToTop = document.getElementById('back-to-top');
  if (!backToTop) return;

  backToTop.addEventListener('click', () => {
    globalThis.scrollTo({ top: 0, behavior: 'smooth' });
  });
}

/**
 * Unified scroll handler — combines nav scroll spy, header shadow, and back-to-top
 * into a single requestAnimationFrame-gated listener to avoid layout thrashing.
 */
export function initScrollHandlers(): void {
  const header = document.querySelector('.header');
  const backToTop = document.getElementById('back-to-top');

  let ticking = false;
  globalThis.addEventListener('scroll', () => {
    if (!ticking) {
      globalThis.requestAnimationFrame(() => {
        // Nav scroll spy
        if (_pendingNavSections) {
          updateActiveNavOnScroll(_pendingNavSections);
        }

        // Header shadow
        if (header) {
          if (globalThis.scrollY > 10) {
            header.classList.add('scrolled');
          } else {
            header.classList.remove('scrolled');
          }
        }

        // Back to top visibility
        if (backToTop) {
          if (globalThis.scrollY > 400) {
            backToTop.classList.add('visible');
          } else {
            backToTop.classList.remove('visible');
          }
        }

        ticking = false;
      });
      ticking = true;
    }
  });
}

/**
 * Initialize Service Worker for PWA
 */
export function initServiceWorker(): void {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker
      .register('/static/sw.js')
      .then(() => {
        if (DEBUG) console.log('Service Worker registered');
      })
      .catch((err) => {
        console.log('Service Worker registration failed:', err);
      });
  }
}
