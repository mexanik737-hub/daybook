using System.Text.Json;
using Daybook.Windows.Models;

namespace Daybook.Windows.Services;

/// <summary>
/// Three-way, per-entry merge for clients sharing one Daybook Drive document.
/// Missing entries represent deletions. Concurrent edits to the same entry
/// are surfaced as conflicts rather than silently overwritten.
/// </summary>
public static class DaybookSyncMerger
{
    public sealed record MergeResult(DaybookDocument? Document, IReadOnlyList<string> Conflicts)
    {
        public bool Succeeded => Document is not null && Conflicts.Count == 0;
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = false
    };

    public static MergeResult Merge(
        DaybookDocument baseline,
        DaybookDocument local,
        DaybookDocument remote)
    {
        var conflicts = new List<string>();

        List<DaybookEntry> MergeKind(
            string kind,
            IReadOnlyCollection<DaybookEntry> beforeEntries,
            IReadOnlyCollection<DaybookEntry> localEntries,
            IReadOnlyCollection<DaybookEntry> remoteEntries)
        {
            var before = beforeEntries.ToDictionary(x => x.Id);
            var localMap = localEntries.ToDictionary(x => x.Id);
            var remoteMap = remoteEntries.ToDictionary(x => x.Id);
            var ids = before.Keys.Concat(localMap.Keys).Concat(remoteMap.Keys)
                .Distinct(StringComparer.Ordinal);
            var merged = new List<DaybookEntry>();

            foreach (var id in ids)
            {
                before.TryGetValue(id, out var oldValue);
                localMap.TryGetValue(id, out var localValue);
                remoteMap.TryGetValue(id, out var remoteValue);

                DaybookEntry? chosen;
                if (Same(localValue, remoteValue))
                    chosen = localValue;
                else if (Same(localValue, oldValue))
                    chosen = remoteValue;
                else if (Same(remoteValue, oldValue))
                    chosen = localValue;
                else
                {
                    conflicts.Add($"{kind}:{id}");
                    continue;
                }

                if (chosen is not null)
                    merged.Add(chosen);
            }

            return merged.OrderByDescending(x => x.Updated).ToList();
        }

        var tasks = MergeKind("task", baseline.Tasks, local.Tasks, remote.Tasks);
        var notes = MergeKind("note", baseline.Notes, local.Notes, remote.Notes);

        return conflicts.Count == 0
            ? new MergeResult(new DaybookDocument { Tasks = tasks, Notes = notes }, [])
            : new MergeResult(null, conflicts);
    }

    private static bool Same(DaybookEntry? left, DaybookEntry? right)
    {
        if (left is null || right is null) return left is null && right is null;
        return left.Id == right.Id &&
               left.Title == right.Title &&
               left.Body == right.Body &&
               left.Created == right.Created &&
               left.Updated == right.Updated &&
               left.Due == right.Due &&
               left.Priority == right.Priority &&
               left.Done == right.Done;
    }
}
