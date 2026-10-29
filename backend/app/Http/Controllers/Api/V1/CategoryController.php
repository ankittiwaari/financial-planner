<?php

namespace App\Http\Controllers\Api\V1;

use App\Enums\CategoryKind;
use App\Http\Controllers\Controller;
use App\Http\Requests\CategoryRequest;
use App\Http\Resources\CategoryResource;
use App\Models\Category;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Http\Response;
use Illuminate\Validation\Rule;

class CategoryController extends Controller
{
    public function index(Request $request): AnonymousResourceCollection
    {
        $request->validate(['kind' => ['sometimes', Rule::enum(CategoryKind::class)]]);

        $categories = $request->user()->categories()
            ->when($request->filled('kind'), fn ($q) => $q->where('kind', $request->string('kind')->value()))
            ->unless($request->boolean('include_archived'), fn ($q) => $q->active())
            ->orderBy('kind')
            ->orderBy('name')
            ->get();

        return CategoryResource::collection($categories);
    }

    public function store(CategoryRequest $request): JsonResponse
    {
        $category = $request->user()->categories()->create($request->validated());

        return CategoryResource::make($category)->response()->setStatusCode(201);
    }

    public function update(CategoryRequest $request, Category $category): CategoryResource
    {
        $category->update($request->validated());

        return CategoryResource::make($category);
    }

    /** Archives: existing transactions keep the category; pickers stop offering it. */
    public function destroy(Category $category): Response
    {
        $category->forceFill(['archived_at' => now()])->save();

        return response()->noContent();
    }
}
