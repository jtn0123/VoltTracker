/**
 * VoltTracker - UI Module
 * Theme, date picker, navigation, and general UI initialization
 */

import { DEBUG, state } from '@/core';
import { loadTrips } from '@/trips';
import { Theme } from '@/types/enums';

/**
 * Initialize theme from localStorage
 */
export function initTheme(): void {
  const savedTheme = (localStorage.getItem('theme') as Theme) || Theme.Dark;
  document.documentElement.setAttribute('data-theme', savedTheme);
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
  const currentTheme = document.documentElement.getAttribute('data-theme');
  const newTheme = currentTheme === Theme.Dark ? Theme.Light : Theme.Dark;
  document.documentElement.setAttribute('data-theme', newTheme);
  localStorage.setItem('theme', newTheme);
  updateThemeIcon(newTheme);

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

/**
 * Initialize bottom navigation with scroll spy
 */
export function initBottomNav(): void {
  const bottomNav = document.querySelector('.bottom-nav');
  if (!bottomNav) return;

  const navItems = bottomNav.querySelectorAll('.bottom-nav-item');
  const sections: NavSection[] = [
    { id: 'summary-section', nav: 'summary' },
    { id: 'trips-section', nav: 'trips' },
    { id: 'charging-section', nav: 'charging' },
    { id: 'soc-section', nav: 'analysis' },
  ];

  navItems.forEach((item) => {
    item.addEventListener('click', (e) => {
      e.preventDefault();
      const anchor = item as HTMLAnchorElement;
      const sectionId = anchor.getAttribute('href')?.substring(1);
      if (!sectionId) return;
      const section = document.getElementById(sectionId);
      if (section) {
        const headerOffset = 80;
        const elementPosition = section.getBoundingClientRect().top;
        const offsetPosition = elementPosition + window.pageYOffset - headerOffset;

        window.scrollTo({ top: offsetPosition, behavior: 'smooth' });
        setActiveNavItem((item as HTMLElement).dataset.section || '');
      }
    });
  });

  // Scroll handler registration is deferred to initScrollHandlers()
  _pendingNavSections = sections;
}

let _pendingNavSections: NavSection[] | null = null;

/**
 * Update active nav item based on scroll position
 */
export function updateActiveNavOnScroll(sections: NavSection[]): void {
  const scrollPosition = window.scrollY + 100;

  for (let i = sections.length - 1; i >= 0; i--) {
    const section = document.getElementById(sections[i].id);
    if (section && section.offsetTop <= scrollPosition) {
      setActiveNavItem(sections[i].nav);
      break;
    }
  }
}

/**
 * Set active navigation item
 */
export function setActiveNavItem(sectionName: string): void {
  const navItems = document.querySelectorAll('.bottom-nav-item');
  navItems.forEach((item) => {
    const el = item as HTMLElement;
    if (el.dataset.section === sectionName) {
      item.classList.add('active');
    } else {
      item.classList.remove('active');
    }
  });
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
    window.scrollTo({ top: 0, behavior: 'smooth' });
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
  window.addEventListener('scroll', () => {
    if (!ticking) {
      window.requestAnimationFrame(() => {
        // Nav scroll spy
        if (_pendingNavSections) {
          updateActiveNavOnScroll(_pendingNavSections);
        }

        // Header shadow
        if (header) {
          if (window.scrollY > 10) {
            header.classList.add('scrolled');
          } else {
            header.classList.remove('scrolled');
          }
        }

        // Back to top visibility
        if (backToTop) {
          if (window.scrollY > 400) {
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
