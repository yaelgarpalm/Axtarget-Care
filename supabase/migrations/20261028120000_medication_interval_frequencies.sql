-- Medicamentos administrados por intervalo ("cada 8 horas", "cada 12 horas", etc.).
-- Los horarios siguen siendo horas HH:mm dentro del mismo día; la app los repite a diario.
begin;

alter table public.medications drop constraint if exists medications_frequency_check;
alter table public.medications add constraint medications_frequency_check check (
    frequency in (
        'Diaria', 'Cada 2 días', 'Cada 3 días', 'Semanal',
        'Cada 4 horas', 'Cada 6 horas', 'Cada 8 horas', 'Cada 12 horas', 'Cada 24 horas'
    )
);

commit;
